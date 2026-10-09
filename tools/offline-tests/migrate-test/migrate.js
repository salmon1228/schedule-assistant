// 用真实源码里的 DDL 模拟一次数据库升级(v1 → v2 → v3),确认老数据不丢。
// 思路:从 DbHelper.kt 里把 CREATE TABLE 语句原样抠出来(替换掉 Kotlin 的表名常量),
//       先建一个"v1 库"并塞进数据,再执行升级路径要跑的那几条建表语句。
const fs = require('fs');
const { DatabaseSync } = require('node:sqlite');
const path = require('path');

const dbHelperPath = 'D:\\AI\\sdh\\日程\\app\\src\\main\\java\\com\\sdh\\schedule\\data\\DbHelper.kt';
const src = fs.readFileSync(dbHelperPath, 'utf8');

// 1) 表名常量 → 真实表名
const names = {
  '$T_EVENT': 'events',
  '$T_SUGGESTION': 'suggestions',
  '$T_LOG': 'import_log',
  '$T_NOTE': 'notes',
  '$T_COURSE': 'courses',
  '$T_TODO': 'todos',
};

// 2) 抠出所有 """ ... """ 里的 CREATE TABLE 语句
const ddlBlocks = [...src.matchAll(/"""([\s\S]*?)"""/g)]
  .map(m => m[1])
  .filter(s => s.includes('CREATE TABLE'))
  .map(s => {
    let out = s;
    for (const [k, v] of Object.entries(names)) out = out.split(k).join(v);
    return out.trim();
  });

console.log(`从源码里抠出 ${ddlBlocks.length} 条 CREATE TABLE 语句`);
const find = (table) => ddlBlocks.find(s => s.includes(`CREATE TABLE IF NOT EXISTS ${table}`) || s.includes(`CREATE TABLE ${table}`));
const eventDdl = ddlBlocks.find(s => /CREATE TABLE\s+events/.test(s));
const sugDdl = ddlBlocks.find(s => /CREATE TABLE\s+suggestions/.test(s));
const logDdl = ddlBlocks.find(s => /CREATE TABLE\s+import_log/.test(s));
const noteDdl = find('notes');
const courseDdl = find('courses');
const todoDdl = find('todos');

let pass = 0, fail = 0;
const out = [];
function check(name, ok, detail = '') {
  if (ok) { pass++; out.push(`[PASS] ${name}`); }
  else { fail++; out.push(`[FAIL] ${name}  -> ${detail}`); }
}
function must(name, ddl) { check(`DDL 存在: ${name}`, !!ddl, '源码里没找到'); return ddl; }

must('events', eventDdl);
must('suggestions', sugDdl);
must('import_log', logDdl);
must('notes', noteDdl);
must('courses', courseDdl);
must('todos', todoDdl);

// 3) 升级路径绝不包含 DROP
const upgradeBody = (src.match(/override fun onUpgrade[\s\S]*?\n    }/) || [''])[0];
check('onUpgrade 里没有 DROP TABLE', !/DROP\s+TABLE/i.test(upgradeBody), upgradeBody.slice(0, 120));
check('onUpgrade 建 notes/courses/todos 三张表',
  /createNoteTable/.test(upgradeBody) && /createCourseTable/.test(upgradeBody) && /createTodoTable/.test(upgradeBody));

// 4) 用真实 DDL 建一个 v1 库并塞数据
const dbPath = path.join('D:\\AI\\sdh\\日程\\_env\\migrate-test', 'v1.db');
fs.mkdirSync(path.dirname(dbPath), { recursive: true });
if (fs.existsSync(dbPath)) fs.unlinkSync(dbPath);
const db = new DatabaseSync(dbPath);

db.exec(eventDdl);
db.exec(sugDdl);
db.exec(logDdl);
console.log('已按源码 DDL 建好 v1 库(events/suggestions/import_log)');

const now = Date.now();
db.prepare(`insert into events (title,note,location,start_ms,end_ms,all_day,type,repeat,repeat_weekday,repeat_until,
  reminder_min,alarm_reminder,countdown_reminder,account,time_zone,color_index,priority,source,source_text,source_package,
  created_at,updated_at,deleted,completed) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`)
  .run('老日程A', '备注', '望京SOHO', now, now + 3600000, 0, 'SCHEDULE', 'NONE', 0, 0,
       10, 0, 0, '本地日历', '', 0, 'NORMAL', 'MANUAL', '', '', now, now, 0, 0);
db.prepare(`insert into events (title,note,location,start_ms,end_ms,all_day,type,repeat,repeat_weekday,repeat_until,
  reminder_min,alarm_reminder,countdown_reminder,account,time_zone,color_index,priority,source,source_text,source_package,
  created_at,updated_at,deleted,completed) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`)
  .run('老日程B', '', '', now + 86400000, now + 90000000, 0, 'SCHEDULE', 'NONE', 0, 0,
       10, 0, 0, '本地日历', '', 0, 'NORMAL', 'MANUAL', '', '', now, now, 0, 0);
db.prepare(`insert into suggestions (title,note,location,start_ms,end_ms,all_day,repeat,repeat_weekday,reminder_min,
  confidence,engine,source,source_text,source_package,image_path,created_at,status)
  values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`)
  .run('老待确认', '', '', now, now + 3600000, 0, 'NONE', 0, -1, 0.9, 'llm', 'NOTIFICATION_TEXT', '原文', 'com.tencent.mm', '', now, 'PENDING');
db.prepare(`insert into import_log (at_ms,source,source_package,raw_text,engine,outcome,note) values (?,?,?,?,?,?,?)`)
  .run(now, 'NOTIFICATION_TEXT', 'com.tencent.mm', '原文', 'llm', '待确认', '老日志');

const beforeEvents = db.prepare('select count(*) c from events').get().c;
const beforeSug = db.prepare('select count(*) c from suggestions').get().c;
const beforeLog = db.prepare('select count(*) c from import_log').get().c;
console.log(`升级前: 日程 ${beforeEvents} / 待确认 ${beforeSug} / 日志 ${beforeLog}`);

// 5) 执行升级(v1 → v3):只跑"补齐新表"那几条
db.exec(noteDdl);
db.exec(courseDdl);
db.exec(todoDdl);
console.log('已执行升级:新建 notes/courses/todos');

const afterEvents = db.prepare('select count(*) c from events').get().c;
const afterSug = db.prepare('select count(*) c from suggestions').get().c;
const afterLog = db.prepare('select count(*) c from import_log').get().c;
const tables = db.prepare("select name from sqlite_master where type='table' order by name").all().map(r => r.name);
console.log(`升级后: 日程 ${afterEvents} / 待确认 ${afterSug} / 日志 ${afterLog}`);
console.log('表: ' + tables.join(', '));

check('升级后日程数量不变', afterEvents === beforeEvents, `${beforeEvents} -> ${afterEvents}`);
check('升级后待确认数量不变', afterSug === beforeSug, `${beforeSug} -> ${afterSug}`);
check('升级后日志数量不变', afterLog === beforeLog, `${beforeLog} -> ${afterLog}`);
check('老日程内容还在', db.prepare("select count(*) c from events where title='老日程A'").get().c === 1);
check('notes 表已创建', tables.includes('notes'));
check('courses 表已创建', tables.includes('courses'));
check('todos 表已创建', tables.includes('todos'));

// 6) 新表可写可读
db.prepare(`insert into notes (title,content,pinned,color_index,created_at,updated_at,deleted) values (?,?,?,?,?,?,?)`)
  .run('新便签', '内容', 1, 2, now, now, 0);
db.prepare(`insert into todos (title,note,done,due_ms,priority,color_index,source,source_text,source_package,
  related_event_id,created_at,updated_at,done_at,deleted) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)`)
  .run('准备一寸照片', '', 0, 0, 'NORMAL', 0, 'NOTIFICATION_TEXT', '原文', 'com.tencent.mm', 0, now, now, 0, 0);
check('新表 notes 可读写', db.prepare('select count(*) c from notes').get().c === 1);
check('新表 todos 可读写', db.prepare('select title from todos').get().title === '准备一寸照片');

// 7) 再"升级一次"(幂等性):重复执行不能报错、也不能丢数据
let againOk = true;
try {
  db.exec(noteDdl); db.exec(courseDdl); db.exec(todoDdl);
} catch (e) { againOk = false; }
check('重复执行升级不报错(IF NOT EXISTS)', againOk);
check('重复升级后数据仍在', db.prepare('select count(*) c from events').get().c === beforeEvents);

db.close();

const resDir = 'D:\\AI\\sdh\\日程\\_env\\migrate-test';
fs.writeFileSync(path.join(resDir, 'result.txt'),
  `总计 ${pass + fail} 断言,通过 ${pass},失败 ${fail}\n${'='.repeat(60)}\n` + out.join('\n') + '\n' +
  (fail === 0 ? 'ALL CHECKS PASSED\n' : 'SOME CHECKS FAILED\n'), 'utf8');
console.log(`\ntotal=${pass + fail} pass=${pass} fail=${fail}`);
