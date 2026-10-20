# frozen_string_literal: true

require "digest"
require "fileutils"
require "json"
require "open3"
require "openssl"
require "pathname"
require "tmpdir"
require "time"

# Invoked by Fastlane. Never prints commands, signing values, or raw subprocess logs.
module RoomoteRelease
  class Error < StandardError; end
  PACKAGE = "com.elvi.tvremote"
  AUDIT_CHECKS = %w[
    app_identity signing_and_version target_api release_testing
    store_listing_and_graphics privacy_and_data_safety target_audience_and_families
    content_rating ads_and_app_access pricing_and_countries production_access
    publishing_overview
  ].freeze
  module_function

  def run!(*args, env: {}, purpose:)
    output, _errors, status = Open3.capture3(env, *args)
    raise Error, "#{purpose} failed (exit #{status.exitstatus}); raw output withheld to protect signing values." unless status.success?
    output
  rescue Errno::ENOENT
    raise Error, "#{purpose} tool is unavailable."
  end

  def private_file!(path, label)
    raise Error, "#{label} must be a regular, non-symlink file." unless File.file?(path) && !File.symlink?(path)
    stat = File.stat(path)
    unless stat.uid == Process.uid && (stat.mode & 0o077).zero?
      raise Error, "#{label} must be owned by the current user with no group/other permissions."
    end
  end

  def signing
    require "dotenv"
    path = File.expand_path("~/.android-dev/secrets/#{PACKAGE}/signing.env")
    private_file!(path, "Signing configuration")
    begin
      values = Dotenv.parse(path)
    rescue StandardError
      raise Error, "Signing configuration could not be parsed; contents withheld."
    end
    required = %w[ANDROID_KEYSTORE_FILE ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_ALIAS ANDROID_KEY_PASSWORD]
    raise Error, "Signing configuration is incomplete." unless required.all? { |name| !values[name].to_s.empty? }
    values["ANDROID_KEYSTORE_FILE"] = File.expand_path(values.fetch("ANDROID_KEYSTORE_FILE"), File.dirname(path))
    private_file!(values.fetch("ANDROID_KEYSTORE_FILE"), "Upload keystore")
    values
  rescue LoadError
    raise Error, "The Fastlane dotenv dependency is unavailable."
  end

  def require_version!(version)
    code = version["versionCode"].to_s
    name = version["versionName"].to_s
    unless code.match?(/\A[1-9]\d*\z/) && code.to_i <= 2_100_000_000 &&
           name.match?(/\A[0-9]+(?:\.[0-9]+){1,3}(?:[-+][A-Za-z0-9.-]+)?\z/)
      raise Error, "Supply explicit valid version_code: and version_name: values."
    end
  end

  def signed_build(project:, app_module:, flavor:, kind:, version:)
    require_version!(version)
    raise Error, "Unsupported artifact type." unless %w[aab apk].include?(kind)
    raise Error, "Invalid module/flavor." unless app_module.match?(/\A[A-Za-z0-9_-]+\z/) && flavor.match?(/\A[A-Za-z0-9_]*\z/)
    secrets = signing
    injected = {
      "android.injected.signing.store.file" => secrets.fetch("ANDROID_KEYSTORE_FILE"),
      "android.injected.signing.store.password" => secrets.fetch("ANDROID_KEYSTORE_PASSWORD"),
      "android.injected.signing.key.alias" => secrets.fetch("ANDROID_KEY_ALIAS"),
      "android.injected.signing.key.password" => secrets.fetch("ANDROID_KEY_PASSWORD"),
    }.transform_keys { |key| "ORG_GRADLE_PROJECT_#{key}" }
    variant = "#{flavor}Release".sub(/\A./, &:upcase)
    task = "#{app_module}:#{kind == 'aab' ? 'bundle' : 'assemble'}#{variant}"
    run!(File.join(project, "gradlew"), "-p", project, task,
         "-PversionCode=#{version.fetch('versionCode')}", "-PversionName=#{version.fetch('versionName')}",
         "--console=plain", "--no-daemon", env: injected, purpose: "Signed Gradle build")
    lower_variant = flavor.empty? ? "release" : "#{flavor}Release"
    output_dir = File.join(project, app_module, "build", "outputs",
                           kind == "aab" ? "bundle" : "apk",
                           kind == "apk" && !flavor.empty? ? "#{flavor}/release" : lower_variant)
    candidates = Dir[File.join(output_dir, "*.#{kind}")].reject { |path| path.end_with?("-unsigned.apk") }
    raise Error, "Expected exactly one signed #{kind.upcase} output." unless candidates.one?
    artifact = candidates.first
    certificate = signer_certificate(artifact, kind)
    expected = run!("keytool", "-list", "-rfc", "-keystore", secrets.fetch("ANDROID_KEYSTORE_FILE"),
                    "-alias", secrets.fetch("ANDROID_KEY_ALIAS"), "-storepass:env", "ROOMOTE_RELEASE_STORE_PASSWORD",
                    env: { "ROOMOTE_RELEASE_STORE_PASSWORD" => secrets.fetch("ANDROID_KEYSTORE_PASSWORD") },
                    purpose: "Upload certificate inspection")
    expected_fingerprint = Digest::SHA256.hexdigest(OpenSSL::X509::Certificate.new(expected).to_der)
    raise Error, "Artifact signer does not match the configured upload certificate." unless certificate == expected_fingerprint
    result = { path: artifact, signer_sha256: certificate, version: version }
    if kind == "aab"
      run!("bundletool", "validate", "--bundle=#{artifact}", purpose: "App Bundle validation")
      fields = {
        "package_name" => "/manifest/@package",
        "version_code" => "/manifest/@android:versionCode",
        "version_name" => "/manifest/@android:versionName",
        "target_sdk" => "/manifest/uses-sdk/@android:targetSdkVersion",
      }.transform_values do |xpath|
        run!("bundletool", "dump", "manifest", "--bundle=#{artifact}", "--xpath=#{xpath}",
             purpose: "Bundle manifest inspection").strip.delete_prefix('"').delete_suffix('"')
      end
      unless fields["package_name"] == PACKAGE && fields["version_code"] == version["versionCode"] &&
             fields["version_name"] == version["versionName"]
        raise Error, "Built package/version does not match the explicitly requested release."
      end
      raise Error, "This phone release must target API 36 or higher for the current Play requirement." if fields["target_sdk"].to_i < 36
      mapping = File.join(project, app_module, "build", "outputs", "mapping", lower_variant, "mapping.txt")
      raise Error, "Release obfuscation mapping is missing/empty." unless File.file?(mapping) && File.size(mapping).positive?
      result.merge!(fields: fields, mapping: mapping)
    end
    result
  rescue OpenSSL::X509::CertificateError
    raise Error, "The signing certificate could not be parsed."
  end

  def signer_certificate(path, kind = "aab")
    if kind == "aab"
      result = run!("jarsigner", "-J-Duser.language=en", "-verify", path, purpose: "AAB signature verification")
      unless result.include?("jar verified.") && !result.include?("unsigned entries")
        raise Error, "Release AAB is unsigned or contains unverified entries."
      end
      certificate = run!("keytool", "-printcert", "-rfc", "-jarfile", path, purpose: "AAB signer inspection")
      Digest::SHA256.hexdigest(OpenSSL::X509::Certificate.new(certificate).to_der)
    else
      result = run!("apksigner", "verify", "--print-certs", path, purpose: "APK signature verification")
      result[/Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F]+)/, 1]&.downcase ||
        raise(Error, "APK signer fingerprint is missing.")
    end
  end

  def inventory(directory)
    files = {}
    Dir.glob(File.join(directory, "**", "*"), File::FNM_DOTMATCH).sort.each do |path|
      next if [".", ".."].include?(File.basename(path))
      raise Error, "Frozen inputs must not contain symlinks." if File.symlink?(path)
      next if File.directory?(path)
      raise Error, "Frozen inputs must be regular files." unless File.file?(path)
      relative = Pathname.new(path).relative_path_from(Pathname.new(directory)).to_s
      files[relative] = Digest::SHA256.file(path).hexdigest
    end
    files
  end

  def freeze(artifact:, project:, destination:, metadata:)
    raise Error, "Freeze destination already exists." if File.exist?(destination) || File.symlink?(destination)
    raise Error, "Store metadata is missing." unless File.directory?(metadata)
    inventory(metadata)
    code = artifact.fetch(:fields).fetch("version_code")
    { "title.txt" => 30, "short_description.txt" => 80, "full_description.txt" => 4000 }.each do |name, maximum|
      path = File.join(metadata, "en-US", name)
      raise Error, "English listing text is incomplete." unless File.file?(path) && !File.read(path).strip.empty?
      raise Error, "English #{name} exceeds the Play character limit." if File.read(path).strip.length > maximum
    end
    changelog = File.join(metadata, "en-US", "changelogs", "#{code}.txt")
    raise Error, "Version-specific release notes are missing." unless File.file?(changelog) && !File.read(changelog).strip.empty?
    raise Error, "Release notes exceed the Play character limit." if File.read(changelog).strip.length > 500
    FileUtils.mkdir_p(File.dirname(destination))
    temporary = Dir.mktmpdir(".roomote-freeze-", File.dirname(destination))
    begin
      FileUtils.cp(artifact.fetch(:path), File.join(temporary, "app.aab"))
      FileUtils.cp(artifact.fetch(:mapping), File.join(temporary, "mapping.txt"))
      FileUtils.cp_r(metadata, File.join(temporary, "metadata"))
      manifest = {
        "schema_version" => 1, "package_name" => PACKAGE,
        "version_code" => code.to_i,
        "version_name" => artifact.fetch(:fields).fetch("version_name"),
        "target_sdk" => artifact.fetch(:fields).fetch("target_sdk").to_i,
        "created_at_utc" => Time.now.utc.iso8601,
        "signer_certificate_sha256" => artifact.fetch(:signer_sha256),
        "files" => inventory(temporary),
      }
      File.write(File.join(temporary, "manifest.json"), JSON.pretty_generate(manifest) + "\n")
      inventory(temporary).each_key { |relative| File.chmod(0o444, File.join(temporary, relative)) }
      File.rename(temporary, destination)
      File.join(destination, "manifest.json")
    ensure
      FileUtils.rm_rf(temporary) if File.exist?(temporary)
    end
  end

  def verify_snapshot(manifest_path)
    raise Error, "Manifest must be a regular file." unless File.file?(manifest_path) && !File.symlink?(manifest_path)
    manifest = JSON.parse(File.read(manifest_path))
    raise Error, "Unsupported release manifest." unless manifest["schema_version"] == 1 && manifest["package_name"] == PACKAGE
    root = File.dirname(File.realpath(manifest_path))
    expected = manifest.fetch("files")
    current = inventory(root)
    current.delete("manifest.json")
    raise Error, "Frozen contents changed; freeze and review a new artifact." unless current == expected
    %w[app.aab mapping.txt].each { |name| raise(Error, "Frozen release is incomplete.") unless expected.key?(name) }
    unless signer_certificate(File.join(root, "app.aab")) == manifest["signer_certificate_sha256"]
      raise Error, "Frozen AAB signer does not match its manifest."
    end
    { manifest: manifest, aab: File.join(root, "app.aab"),
      mapping: File.join(root, "mapping.txt"), metadata: File.join(root, "metadata") }
  rescue JSON::ParserError, KeyError, ArgumentError
    raise Error, "Release manifest is invalid/incomplete."
  end

  def finalize_listing(manifest_path:, tested_aab_sha256:, project:, destination:, metadata:)
    candidate = verify_snapshot(manifest_path)
    manifest = candidate.fetch(:manifest)
    unless tested_aab_sha256.to_s.match?(/\A[0-9a-f]{64}\z/) &&
           tested_aab_sha256 == manifest.fetch("files").fetch("app.aab")
      raise Error, "The candidate must match the explicitly supplied tested AAB SHA-256."
    end
    artifact = {
      path: candidate.fetch(:aab), mapping: candidate.fetch(:mapping),
      signer_sha256: manifest.fetch("signer_certificate_sha256"),
      fields: {
        "version_code" => manifest.fetch("version_code").to_s,
        "version_name" => manifest.fetch("version_name"),
        "target_sdk" => manifest.fetch("target_sdk").to_s,
      },
    }
    freeze(artifact: artifact, project: project, destination: destination, metadata: metadata)
  end

  def verify_prepared(manifest_path, audit_path, operation:)
    raise Error, "Audit must be a regular file." unless File.file?(audit_path) && !File.symlink?(audit_path)
    prepared = verify_snapshot(manifest_path)
    audit = JSON.parse(File.read(audit_path))
    validate_audit!(audit, prepared.fetch(:manifest), Digest::SHA256.file(manifest_path).hexdigest, operation)
    prepared.merge(audit: audit)
  rescue JSON::ParserError, KeyError, ArgumentError
    raise Error, "Release manifest or Console audit is invalid/incomplete."
  end

  def validate_audit!(audit, manifest, manifest_hash, operation)
    unless audit["schema_version"] == 1 && audit["operation"] == operation &&
           audit["console_audit_complete"] == true && audit["publication_authorized"] == true &&
           audit["package_name"] == manifest["package_name"] &&
           audit["version_code"] == manifest["version_code"] &&
           audit["version_name"] == manifest["version_name"] &&
           audit["prepared_manifest_sha256"] == manifest_hash &&
           audit["aab_sha256"] == manifest.fetch("files").fetch("app.aab")
      raise Error, "A completed, authorized Console audit matching this exact artifact/version is required."
    end
    age = Time.now.utc - Time.iso8601(audit.fetch("reviewed_at_utc"))
    raise Error, "Console audit must be current (within 24 hours, not in the future)." unless age.between?(-60, 86_400)
    raise Error, "Console reviewer is missing." if audit["reviewed_by"].to_s.strip.empty?
    checks = audit.fetch("checks")
    AUDIT_CHECKS.each do |name|
      check = checks.fetch(name)
      unless check["status"] == "passed" && !check["evidence"].to_s.strip.empty?
        raise Error, "Audit '#{name}' needs a passed result and concrete evidence."
      end
    end
    unless %w[internal alpha beta production].include?(audit["track"]) &&
           %w[draft completed inProgress].include?(audit["release_status"])
      raise Error, "Unreviewed track or release status."
    end
    %w[changes_not_sent_for_review upload_metadata upload_images upload_screenshots].each do |name|
      raise Error, "Audit must explicitly set #{name} true/false." unless [true, false].include?(audit[name])
    end
    rollout = audit["rollout"]
    if audit["release_status"] == "inProgress"
      raise Error, "Staged rollout needs a reviewed fraction between 0 and 1." unless rollout.is_a?(Numeric) && rollout.positive? && rollout < 1
    elsif !rollout.nil?
      raise Error, "Only inProgress releases may specify a rollout fraction."
    end
    if operation == "promote" && !%w[internal alpha beta production].include?(audit["source_track"])
      raise Error, "Promotion requires an explicit source_track."
    end
  end
end
