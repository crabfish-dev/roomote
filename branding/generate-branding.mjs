import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
let sharp;
try {
  sharp = require("sharp");
} catch {
  if (!process.env.ROOMOTE_NODE_MODULES) throw new Error("Install sharp or set ROOMOTE_NODE_MODULES to a directory containing sharp.");
  sharp = require(path.join(process.env.ROOMOTE_NODE_MODULES, "sharp"));
}

const directory = path.dirname(fileURLToPath(import.meta.url));
const root = path.dirname(directory);
const color = { navy: "#1F2B36", clay: "#A64B2E", ivory: "#F8F7F2", peach: "#FAE7DA", muted: "#5F6767" };
// Original R silhouette. Its round counter and central button echo a remote D-pad.
const letter = "M24 75V30C24 22 30 16 38 16H50C66 16 76 26 76 40C76 50 71 58 62 62L77 76Q80 80 74 80H61Q59 80 58 78L43 59H38V75Q38 80 33 80H29Q24 80 24 75Z";
const counter = "M62 39A12 12 0 1 1 38 39A12 12 0 1 1 62 39Z";
const center = "M56 39A6 6 0 1 1 44 39A6 6 0 1 1 56 39Z";
const mark = (fill = color.ivory, button = color.clay) =>
  `<path d="${letter} ${counter}" fill="${fill}" fill-rule="evenodd"/><path d="${center}" fill="${button}"/>`;
const positioned = (x, y, scale, fill, button) =>
  `<g transform="translate(${x} ${y}) scale(${scale}) translate(-51 -48)">${mark(fill, button)}</g>`;
const svg = (width, height, body, title = "Roomote") =>
  `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" role="img" aria-label="${title}"><title>${title}</title>${body}</svg>\n`;
const write = (name, content) => fs.writeFile(path.join(directory, name), content);
const raster = async (name, source, width, height, opaque = false) => {
  let pipeline = sharp(Buffer.from(source)).resize(width, height);
  if (opaque) pipeline = pipeline.removeAlpha();
  await pipeline.png().toFile(path.join(directory, name));
};

const icon = svg(512, 512,
  `<rect width="512" height="512" fill="${color.navy}"/>${positioned(256, 256, 5.2)}`,
  "Roomote app icon");
await write("roomote-mark.svg", svg(96, 96, positioned(48, 48, 1, color.navy, color.clay), "Roomote R mark"));
await write("roomote-mark-light.svg", svg(96, 96, positioned(48, 48, 1), "Roomote R mark, light"));
await write("roomote-icon.svg", icon);
await write("roomote-wordmark.svg", svg(420, 96,
  `<rect width="96" height="96" rx="24" fill="${color.navy}"/>${positioned(48, 48, .98)}<text x="119" y="65" font-family="Arial, Helvetica, sans-serif" font-size="57" font-weight="700" letter-spacing="-2.7" fill="${color.navy}">Roomote</text>`,
  "Roomote logo"));
await raster("roomote-icon-512.png", icon, 512, 512);

const sample = (x, y, size, background, foreground, button, round = false) =>
  `<rect x="${x}" y="${y}" width="${size}" height="${size}" rx="${round ? size / 2 : size * .24}" fill="${background}"/>${positioned(x + size / 2, y + size / 2, size / 98, foreground, button)}`;
const preview = svg(1024, 1024,
  `<rect width="1024" height="1024" fill="${color.ivory}"/>
   <text x="64" y="106" font-family="Arial, Helvetica, sans-serif" font-size="44" font-weight="700" letter-spacing="-1.8" fill="${color.navy}">Roomote</text>
   <text x="65" y="142" font-family="Arial, Helvetica, sans-serif" font-size="18" fill="${color.muted}">Your TV, within reach.</text>
   ${sample(64, 190, 536, color.navy, color.ivory, color.clay)}
   <text x="668" y="225" font-family="Arial, Helvetica, sans-serif" font-size="14" font-weight="700" letter-spacing="2" fill="${color.muted}">AT A GLANCE</text>
   ${sample(668, 258, 136, color.navy, color.ivory, color.clay, true)}
   ${sample(836, 302, 64, color.navy, color.ivory, color.clay)}
   ${sample(920, 318, 32, color.navy, color.ivory, color.clay)}
   <text x="668" y="446" font-family="Arial, Helvetica, sans-serif" font-size="14" font-weight="700" letter-spacing="2" fill="${color.muted}">THEMED ICONS</text>
   ${sample(668, 478, 120, color.peach, color.clay, color.clay, true)}
   ${sample(820, 478, 120, color.navy, color.peach, color.peach, true)}
   <path d="M64 788H960" stroke="#DFE1D9"/>
   <text x="65" y="847" font-family="Arial, Helvetica, sans-serif" font-size="19" font-weight="700" fill="${color.navy}">A remote. A letter. One familiar shape.</text>
   <text x="65" y="884" font-family="Arial, Helvetica, sans-serif" font-size="17" fill="${color.muted}">An original R monogram with a tactile control at its heart.</text>
   <circle cx="77" cy="943" r="12" fill="${color.navy}"/><circle cx="116" cy="943" r="12" fill="${color.clay}"/><circle cx="155" cy="943" r="12" fill="${color.peach}"/>
   <text x="194" y="949" font-family="Arial, Helvetica, sans-serif" font-size="14" fill="${color.muted}">Navy · Terracotta · Warm ivory</text>`,
  "Roomote logo and adaptive icon preview");
await write("roomote-preview.svg", preview);
await raster("roomote-preview-1024.png", preview, 1024, 1024);

const feature = svg(1024, 500,
  `<rect width="1024" height="500" fill="${color.navy}"/>
   <circle cx="1050" cy="38" r="260" fill="none" stroke="#293947" stroke-width="2"/>
   <circle cx="1040" cy="18" r="330" fill="none" stroke="#293947" stroke-width="2"/>
   <text x="64" y="91" font-family="Arial, Helvetica, sans-serif" font-size="34" font-weight="700" letter-spacing="-1.3" fill="${color.ivory}">Roomote</text>
   <text x="60" y="208" font-family="Arial, Helvetica, sans-serif" font-size="66" font-weight="700" letter-spacing="-2.6" fill="${color.ivory}">Your TV,</text>
   <text x="60" y="282" font-family="Arial, Helvetica, sans-serif" font-size="66" font-weight="700" letter-spacing="-2.6" fill="${color.ivory}">within reach.</text>
   <path d="M64 327H128" stroke="${color.clay}" stroke-width="5" stroke-linecap="round"/>
   <text x="64" y="372" font-family="Arial, Helvetica, sans-serif" font-size="20" fill="#D0D4CF">Wi-Fi remote for Android TV &amp; Google TV</text>
   <text x="64" y="406" font-family="Arial, Helvetica, sans-serif" font-size="16" fill="#B8C0BD">Pair once. Settle in.</text>
   ${positioned(817, 247, 4.9)}`,
  "Roomote — Your TV, within reach. Wi-Fi remote for Android TV and Google TV.");
await write("roomote-feature.svg", feature);
await raster("roomote-feature-1024x500.png", feature, 1024, 500, true);

const vector = (body) => `<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
${body}
</vector>\n`;
const androidMark = (scale, translateX, translateY, monochrome = false) => `    <group android:scaleX="${scale}" android:scaleY="${scale}"
        android:translateX="${translateX}" android:translateY="${translateY}">
        <path android:fillColor="${monochrome ? "#FFFFFFFF" : color.ivory}" android:fillType="evenOdd"
            android:pathData="${letter} ${counter}"/>
        <path android:fillColor="${monochrome ? "#FFFFFFFF" : color.clay}"
            android:pathData="${center}"/>
    </group>`;
const res = path.join(root, "app/src/main/res");
await fs.mkdir(path.join(res, "drawable-anydpi-v26"), { recursive: true });
await fs.mkdir(path.join(res, "drawable-anydpi-v33"), { recursive: true });
await fs.writeFile(path.join(res, "drawable/ic_launcher.xml"), vector(
  `    <path android:fillColor="${color.navy}" android:pathData="M0 0H108V108H0Z"/>\n${androidMark(1.05, .45, 3.6)}`));
await fs.writeFile(path.join(res, "drawable/ic_launcher_background.xml"), vector(
  `    <path android:fillColor="${color.navy}" android:pathData="M0 0H108V108H0Z"/>`));
await fs.writeFile(path.join(res, "drawable/ic_launcher_foreground.xml"), vector(androidMark(.82, 12.18, 14.64)));
await fs.writeFile(path.join(res, "drawable/ic_launcher_monochrome.xml"), vector(androidMark(.82, 12.18, 14.64, true)));
await fs.writeFile(path.join(res, "drawable-anydpi-v26/ic_launcher.xml"), `<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background"/>
    <foreground android:drawable="@drawable/ic_launcher_foreground"/>
</adaptive-icon>\n`);
await fs.writeFile(path.join(res, "drawable-anydpi-v33/ic_launcher.xml"), `<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background"/>
    <foreground android:drawable="@drawable/ic_launcher_foreground"/>
    <monochrome android:drawable="@drawable/ic_launcher_monochrome"/>
</adaptive-icon>\n`);
console.log("Roomote SVG, PNG, and Android icon assets generated.");
