/* Render share-ready layouts from genuine Android screenshots. No screen content is redrawn. */
const fs = require('node:fs');
const path = require('node:path');
const args = process.argv.slice(2);
const option = (name, fallback) => args.includes(name) ? args[args.indexOf(name) + 1] : fallback;
const sharp = require(option('--sharp-module', 'sharp'));
const root = path.resolve(__dirname, '..');
const input = path.resolve(option('--screenshots', path.join(root, 'docs/screenshots/ui-map/page-map')));
const output = path.resolve(option('--output', path.join(root, 'docs/marketing')));
const catalog = {
  censor:{file:'05a-settings-appearance.png', title:'CENSOR', detail:'Choose your look'},
  limits:{file:'02-limits.png', title:'APP LIMITS', detail:'Set the boundaries'},
  tribute:{file:'03-wallet.png', title:'TRIBUTE', detail:'Set your tribute'},
  subliminal:{file:'15-whispers.png', title:'SUBLIMINAL MESSAGES', detail:'Choose your message'},
  studio:{file:'23-wizard-features.png', title:'PACK MAKER', detail:'Make it your own'},
};
const shield = fs.readFileSync(path.join(root, 'app/src/main/res/drawable/ic_nav_censor.xml'), 'utf8')
  .match(/android:pathData="([^"]+)"/)[1];
const escape = value => value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/"/g, '&quot;');
const text = (x,y,size,value,extra='') => `<text x="${x}" y="${y}" font-size="${size}" ${extra}>${escape(value)}</text>`;

async function render(format, width, height, keys) {
  const screens = keys.map(key => catalog[key]);
  const portrait = height > 1080;
  const wide = width > 1080;
  const phoneWidth = wide ? 270 : portrait ? 292 : 260;
  const gap = wide ? 76 : portrait ? 40 : 64;
  const first = (width - phoneWidth * screens.length - gap * (screens.length - 1)) / 2;
  const phoneTop = wide ? 315 : portrait ? 437 : 353;
  const screenWidth = phoneWidth - 18;
  const definitions = [];
  const phones = [];
  for (let i = 0; i < screens.length; i++) {
    const screen = screens[i], x = first + i * (phoneWidth + gap);
    const file = path.join(input, screen.file);
    const meta = await sharp(file).metadata();
    if (!meta.width || !meta.height || meta.height <= meta.width) throw new Error(`Expected portrait capture: ${screen.file}`);
    const screenHeight = Math.round(screenWidth * meta.height / meta.width);
    const phoneHeight = screenHeight + 18;
    if (phoneTop + phoneHeight + 35 > height - 130) throw new Error(`Capture aspect ratio does not fit: ${screen.file}`);
    const data = fs.readFileSync(file).toString('base64');
    definitions.push(`<clipPath id="screen-${i}"><rect x="${x+9}" y="${phoneTop+9}" width="${screenWidth}" height="${screenHeight}" rx="22"/></clipPath>`);
    phones.push(text(x+phoneWidth/2,phoneTop-27,23,screen.title,'text-anchor="middle" fill="#d7bedf" font-weight="700" letter-spacing="2"'));
    phones.push(`<rect x="${x}" y="${phoneTop}" width="${phoneWidth}" height="${phoneHeight}" rx="30" fill="#08070d" stroke="#876a9c" stroke-width="2" filter="url(#shadow)"/>`);
    phones.push(`<image href="data:image/png;base64,${data}" x="${x+9}" y="${phoneTop+9}" width="${screenWidth}" height="${screenHeight}" clip-path="url(#screen-${i})"/>`);
    phones.push(text(x+phoneWidth/2,phoneTop+phoneHeight+35,21,screen.detail,'text-anchor="middle" fill="#eee4f2"'));
  }
  const headlineY = portrait ? 260 : 232;
  const headline = wide
    ? text(840,128,50,'Set the terms.','font-weight="700"') + text(840,190,50,'Hand over control.','font-weight="700" fill="#c8a7d6"')
    : portrait ? text(75,headlineY,54,'Set the terms.','font-weight="700"')+text(75,headlineY+66,54,'Hand over control.','font-weight="700" fill="#c8a7d6"')
    : text(width/2,headlineY,43,'Set the terms. Hand over control.','text-anchor="middle" font-weight="700"');
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}">
  <defs>
    <linearGradient id="bg" x1="0" y1="0" x2="1" y2="1"><stop stop-color="#21142e"/><stop offset=".5" stop-color="#0c0911"/><stop offset="1" stop-color="#27152e"/></linearGradient>
    <radialGradient id="glow"><stop stop-color="#74508b" stop-opacity=".35"/><stop offset="1" stop-color="#74508b" stop-opacity="0"/></radialGradient>
    <filter id="shadow" x="-30%" y="-10%" width="160%" height="125%"><feDropShadow dx="0" dy="18" stdDeviation="18" flood-color="#000" flood-opacity=".65"/></filter>
    ${definitions.join('\n')}
  </defs>
  <rect width="${width}" height="${height}" fill="url(#bg)"/>
  <ellipse cx="${width*.7}" cy="370" rx="${width*.65}" ry="550" fill="url(#glow)"/>
  <path d="M-40 ${height-120} Q${width/2} ${height-410} ${width+40} ${height-65}" fill="none" stroke="#866493" stroke-width="1" opacity=".25"/>
  <path d="M-40 ${height-70} Q${width/2} ${height-320} ${width+40} ${height-15}" fill="none" stroke="#866493" stroke-width="1" opacity=".15"/>
  <g transform="translate(${wide?133:93},75) scale(3.4)"><path d="${shield}" fill="none" stroke="#c7a4d5" stroke-width="1.1" stroke-linecap="round" stroke-linejoin="round"/></g>
  <g font-family="Segoe UI, Arial, sans-serif" fill="#f6eff8">
    ${text(wide?252:212,92,17,'PRIVATE ANDROID CONTROL','fill="#bfa3ca" letter-spacing="4"')}
    ${text(wide?249:209,160,75,'SUBHUB','font-family="Georgia, serif" letter-spacing="10"')}
    ${headline}
    ${text(wide?840:portrait?75:width/2,wide?232:portrait?headlineY+115:headlineY+45,23,'Censor. Tribute. Subliminal messages.',wide||portrait?'fill="#bdaac6"':'text-anchor="middle" fill="#bdaac6"')}
    ${phones.join('\n')}
    <line x1="75" y1="${height-104}" x2="${width-75}" y2="${height-104}" stroke="#5b4268"/>
    ${text(width/2,height-67,20,wide?'On-device detection · Controller PIN · Achievements':'App limits · Pack Maker · Achievements','text-anchor="middle" fill="#baa5c4"')}
    ${text(width/2,height-29,19,'Get SubHub → github.com/confiteor48/SubHub','text-anchor="middle" fill="#d9c8e1"')}
  </g>
  </svg>`;
  fs.mkdirSync(output, {recursive:true});
  const basename = `subhub-social-${format}`;
  fs.writeFileSync(path.join(output, `${basename}.svg`), svg, 'utf8');
  await sharp(Buffer.from(svg)).png().toFile(path.join(output, `${basename}.png`));
  console.log(`${basename}: ${width}×${height}; real screenshot sources: ${screens.map(s=>s.file).join(', ')}`);
}
(async () => {
  await render('square', 1080, 1080, ['censor','tribute','subliminal']);
  await render('portrait', 1080, 1350, ['censor','tribute','subliminal']);
  await render('wide', 1920, 1080, ['censor','limits','tribute','subliminal','studio']);
})()
  .catch(error => { console.error(error.message); process.exitCode = 1; });
