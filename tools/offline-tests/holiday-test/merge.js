// Merge upstream holiday-cn per-year JSON files into one compact assets/holidays.json
// Usage: node merge.js <rawDir> <outFile>
const fs = require('fs');
const path = require('path');

const rawDir = process.argv[2];
const outFile = process.argv[3];

const days = {};      // "YYYY-MM-DD" -> {name, isOffDay}
const yearsFound = new Set();
const yearReport = [];

const files = fs.readdirSync(rawDir).filter(f => /^\d{4}\.json$/.test(f)).sort();
for (const f of files) {
  const y = parseInt(f.slice(0, 4), 10);
  const txt = fs.readFileSync(path.join(rawDir, f), 'utf8');
  const obj = JSON.parse(txt);
  const list = Array.isArray(obj.days) ? obj.days : [];
  let n = 0;
  for (const d of list) {
    const date = String(d.date);
    const name = String(d.name);
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date) || !name) continue;
    const off = d.isOffDay === true;
    if (days[date] && (days[date].name !== name || days[date].isOffDay !== off)) {
      throw new Error('conflict at ' + date + ' between years merged');
    }
    days[date] = { name: name, isOffDay: off };
    n++;
  }
  yearReport.push(f + ': ' + list.length + ' raw rows, ' + n + ' kept');
  if (n > 0) yearsFound.add(y);
}

const years = Array.from(yearsFound).sort((a, b) => a - b);
const keys = Object.keys(days).sort();

const lines = [];
lines.push('{');
lines.push('  "years": [' + years.join(', ') + '],');
lines.push('  "days": {');
keys.forEach((k, i) => {
  const v = days[k];
  const comma = i === keys.length - 1 ? '' : ',';
  lines.push('    "' + k + '": {"name": "' + v.name + '", "isOffDay": ' + (v.isOffDay ? 'true' : 'false') + '}' + comma);
});
lines.push('  }');
lines.push('}');

fs.writeFileSync(outFile, lines.join('\n') + '\n', { encoding: 'utf8' });

console.log('year files: ' + files.join(', '));
yearReport.forEach(r => console.log('  ' + r));
console.log('covered years: ' + JSON.stringify(years));
console.log('total day entries: ' + keys.length);
console.log('off days: ' + keys.filter(k => days[k].isOffDay).length +
            ', makeup workdays: ' + keys.filter(k => !days[k].isOffDay).length);
console.log('date range: ' + keys[0] + ' .. ' + keys[keys.length - 1]);
