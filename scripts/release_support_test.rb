# frozen_string_literal: true

require_relative "release_support"

# Pure local tests. Fake artifact bytes never leave the temporary fixture.
module ReleaseGuardTest
  module_function

  def assert(condition, description)
    raise "FAILED: #{description}" unless condition
    @checks = (@checks || 0) + 1
  end

  def rejects(description)
    yield
    raise "FAILED: #{description} was accepted"
  rescue RoomoteRelease::Error
    @checks = (@checks || 0) + 1
  end

  def copy(value) = Marshal.load(Marshal.dump(value))

  def audit_for(manifest, hash)
    {
      "schema_version" => 1, "operation" => "upload",
      "package_name" => RoomoteRelease::PACKAGE,
      "version_code" => 5, "version_name" => "1.3.0",
      "prepared_manifest_sha256" => hash,
      "aab_sha256" => manifest.fetch("files").fetch("app.aab"),
      "reviewed_at_utc" => Time.now.utc.iso8601,
      "reviewed_by" => "Local automated guard test (not a release approval)",
      "console_audit_complete" => true, "publication_authorized" => true,
      "track" => "production", "release_status" => "draft", "rollout" => nil,
      "changes_not_sent_for_review" => true,
      "upload_metadata" => true, "upload_images" => false, "upload_screenshots" => false,
      "checks" => RoomoteRelease::AUDIT_CHECKS.to_h do |name|
        [name, { "status" => "passed", "evidence" => "Synthetic guard-test fixture only" }]
      end,
    }
  end

  def run
    version = { "versionCode" => "5", "versionName" => "1.3.0" }
    RoomoteRelease.require_version!(version)
    assert(true, "explicit valid version accepted")
    [{}, { "versionCode" => "0", "versionName" => "1.3.0" },
     { "versionCode" => "2100000001", "versionName" => "1.3.0" },
     { "versionCode" => "5", "versionName" => "$(not-a-command)" }].each do |invalid|
      rejects("invalid explicit version") { RoomoteRelease.require_version!(invalid) }
    end

    Dir.mktmpdir("roomote-release-guard-test-") do |tmp|
      metadata = File.join(tmp, "source-metadata")
      english = File.join(metadata, "en-US")
      FileUtils.mkdir_p(File.join(english, "changelogs"))
      { "title.txt" => "Roomote", "short_description.txt" => "Test description",
        "full_description.txt" => "Synthetic test metadata." }.each do |name, value|
        File.write(File.join(english, name), value)
      end
      File.write(File.join(english, "changelogs", "5.txt"), "Synthetic fixture release notes.")
      aab, mapping = %w[source.aab source-mapping.txt].map { |name| File.join(tmp, name) }
      File.write(aab, "Synthetic AAB fixture; not signed or uploadable.")
      File.write(mapping, "synthetic mapping")
      artifact = {
        path: aab, mapping: mapping, signer_sha256: "a" * 64,
        fields: { "version_code" => "5", "version_name" => "1.3.0", "target_sdk" => "36" },
      }
      destination = File.join(tmp, "frozen")
      manifest_path = RoomoteRelease.freeze(artifact: artifact, project: tmp,
        destination: destination, metadata: metadata)
      manifest = JSON.parse(File.read(manifest_path))
      assert(manifest["version_code"] == 5, "frozen manifest version")
      assert(manifest["files"]["app.aab"] == Digest::SHA256.file(aab).hexdigest, "exact AAB bytes frozen")
      assert(manifest["files"]["mapping.txt"] == Digest::SHA256.file(mapping).hexdigest, "exact mapping frozen")
      rejects("overwrite reviewed directory") do
        RoomoteRelease.freeze(artifact: artifact, project: tmp, destination: destination, metadata: metadata)
      end
      File.write(File.join(english, "title.txt"), "Changed source after freeze")
      assert(File.read(File.join(destination, "metadata", "en-US", "title.txt")) == "Roomote", "frozen metadata is independent")
      audit = audit_for(manifest, Digest::SHA256.file(manifest_path).hexdigest)
      validate = ->(record, operation = "upload") do
        RoomoteRelease.validate_audit!(record, manifest, Digest::SHA256.file(manifest_path).hexdigest, operation)
      end
      validate.call(audit)
      assert(true, "complete matching fresh audit accepted")

      {
        "version_code" => 6, "version_name" => "1.3.1", "package_name" => "another.package",
        "prepared_manifest_sha256" => "b" * 64, "aab_sha256" => "c" * 64,
        "console_audit_complete" => false, "publication_authorized" => false,
        "reviewed_by" => "", "reviewed_at_utc" => (Time.now.utc - 86_500).iso8601,
        "release_status" => "unexpected", "track" => "unexpected",
        "changes_not_sent_for_review" => nil, "upload_metadata" => nil,
        "upload_images" => nil, "upload_screenshots" => nil, "rollout" => 0.25,
      }.each do |field, bad_value|
        invalid = copy(audit)
        invalid[field] = bad_value
        rejects("audit #{field} invalid") { validate.call(invalid) }
      end
      invalid = copy(audit)
      invalid["reviewed_at_utc"] = (Time.now.utc + 120).iso8601
      rejects("future audit") { validate.call(invalid) }
      RoomoteRelease::AUDIT_CHECKS.each do |name|
        invalid = copy(audit)
        invalid["checks"][name]["status"] = "pending"
        rejects("pending #{name}") { validate.call(invalid) }
        invalid["checks"][name]["status"] = "passed"
        invalid["checks"][name]["evidence"] = ""
        rejects("empty evidence #{name}") { validate.call(invalid) }
      end
      rejects("audit reused for different operation") { validate.call(audit, "promote") }
      staged = copy(audit)
      staged["release_status"] = "inProgress"
      rejects("staged rollout without fraction") { validate.call(staged) }
      staged["rollout"] = 0.2
      validate.call(staged)
      assert(true, "reviewed staged rollout accepted")
      promotion = copy(audit)
      promotion["operation"] = "promote"
      rejects("promotion without source") { validate.call(promotion, "promote") }
      promotion["source_track"] = "internal"
      validate.call(promotion, "promote")
      assert(true, "explicit promotion source accepted")

      # Stub only cryptographic verification for synthetic bytes; test actual hash/audit path.
      real_signer = RoomoteRelease.method(:signer_certificate)
      RoomoteRelease.define_singleton_method(:signer_certificate) { |_path, _kind = "aab"| "a" * 64 }
      begin
        audit_path = File.join(tmp, "audit.json")
        File.write(audit_path, JSON.generate(audit))
        prepared = RoomoteRelease.verify_prepared(manifest_path, audit_path, operation: "upload")
        assert(prepared[:aab] == File.realpath(File.join(destination, "app.aab")), "upload points only to frozen AAB")
        final_path = RoomoteRelease.finalize_listing(manifest_path: manifest_path,
          tested_aab_sha256: manifest.fetch("files").fetch("app.aab"), project: tmp,
          destination: File.join(tmp, "final"), metadata: metadata)
        final = JSON.parse(File.read(final_path))
        assert(final.fetch("files").fetch("app.aab") == manifest.fetch("files").fetch("app.aab"), "listing finalization preserves tested AAB")
        assert(final.fetch("files").fetch("mapping.txt") == manifest.fetch("files").fetch("mapping.txt"), "listing finalization preserves mapping")
        assert(final.fetch("files").fetch("metadata/en-US/title.txt") != manifest.fetch("files").fetch("metadata/en-US/title.txt"), "listing finalization picks up final assets")
        rejects("listing finalization with untested AAB") do
          RoomoteRelease.finalize_listing(manifest_path: manifest_path,
            tested_aab_sha256: "f" * 64, project: tmp,
            destination: File.join(tmp, "bad-final"), metadata: metadata)
        end
        File.write(File.join(destination, "unreviewed.txt"), "unreviewed")
        rejects("added frozen file") { RoomoteRelease.verify_prepared(manifest_path, audit_path, operation: "upload") }
        File.delete(File.join(destination, "unreviewed.txt"))
        frozen_aab = File.join(destination, "app.aab")
        File.chmod(0o600, frozen_aab)
        File.write(frozen_aab, "changed AAB")
        rejects("tampered frozen AAB") { RoomoteRelease.verify_prepared(manifest_path, audit_path, operation: "upload") }
        FileUtils.cp(aab, frozen_aab)
        frozen_mapping = File.join(destination, "mapping.txt")
        File.delete(frozen_mapping)
        rejects("missing mapping") { RoomoteRelease.verify_prepared(manifest_path, audit_path, operation: "upload") }
        FileUtils.cp(mapping, frozen_mapping)
        File.symlink(aab, File.join(destination, "linked-input"))
        rejects("symlink in frozen files") { RoomoteRelease.verify_prepared(manifest_path, audit_path, operation: "upload") }
        File.delete(File.join(destination, "linked-input"))
        RoomoteRelease.define_singleton_method(:signer_certificate) { |_path, _kind = "aab"| "b" * 64 }
        rejects("mismatched artifact signer") { RoomoteRelease.verify_prepared(manifest_path, audit_path, operation: "upload") }
      ensure
        RoomoteRelease.define_singleton_method(:signer_certificate, real_signer)
      end
      File.write(File.join(english, "title.txt"), "x" * 31)
      rejects("overlong store title") do
        RoomoteRelease.freeze(artifact: artifact, project: tmp,
          destination: File.join(tmp, "overlong"), metadata: metadata)
      end
    end
    puts "#{@checks} release guard assertions passed; no keys, builds or network calls."
  end
end

ReleaseGuardTest.run
