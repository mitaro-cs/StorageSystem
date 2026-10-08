// Нагрузочная проверка: N человек одновременно (по умолчанию 30) входят, держат живые обновления
// (SSE /api/live) и пользуются сайтом – «Сегодня», новости, задания, расписание, предметы,
// отметки «сделано» и комментарии. В конце – время ответа (медиана, 95-й и 99-й процентиль)
// и ошибки по каждому запросу.
//
// Запуск на демо-данных (пароль у всех – demo-password):
//   java -jar target/groupbase.jar seed -d ./data-load
//   java -jar target/groupbase.jar serve -d ./data-load &
//   node scripts/load-test.mjs http://127.0.0.1:8080 30 60
//
// Каждый «человек» приходит со своего адреса (X-Forwarded-For – как через туннель), иначе
// ограничение входа по адресу сработало бы на всех сразу.

const base = process.argv[2] ?? "http://127.0.0.1:8080";
const people = Number(process.argv[3] ?? 30);
const seconds = Number(process.argv[4] ?? 60);
const accounts = (process.env.LOAD_USERS ?? "ivanova").split(",");
const password = process.env.LOAD_PASSWORD ?? "demo-password";

const stats = new Map();
function note(name, ms, ok) {
  const s = stats.get(name) ?? { times: [], errors: 0 };
  s.times.push(ms);
  if (!ok) s.errors++;
  stats.set(name, s);
}

class Client {
  constructor(ip) {
    this.ip = ip;
    this.cookies = new Map();
  }
  headers(extra = {}) {
    const cookie = [...this.cookies].map(([k, v]) => `${k}=${v}`).join("; ");
    return {
      "X-Forwarded-For": this.ip,
      Cookie: cookie,
      Accept: "application/json",
      ...extra,
    };
  }
  keep(res) {
    for (const c of res.headers.getSetCookie?.() ?? []) {
      const [pair] = c.split(";");
      const i = pair.indexOf("=");
      this.cookies.set(pair.slice(0, i), pair.slice(i + 1));
    }
  }
  async call(name, path, init = {}) {
    const t = performance.now();
    let ok = false;
    let body = null;
    try {
      const res = await fetch(base + path, {
        ...init,
        headers: this.headers(init.headers),
        redirect: "manual",
      });
      this.keep(res);
      ok = res.ok;
      body = (res.headers.get("Content-Type") ?? "").includes("json")
        ? await res.json()
        : null;
      if (!ok && process.env.LOAD_DEBUG)
        console.error(name, res.status, JSON.stringify(body));
    } catch (e) {
      if (process.env.LOAD_DEBUG) console.error(name, String(e));
    }
    note(name, performance.now() - t, ok);
    return body;
  }
  write(name, path, method, data) {
    return this.call(name, path, {
      method,
      body: JSON.stringify(data),
      headers: {
        "Content-Type": "application/json",
        "X-CSRF-Token": this.cookies.get("gb_csrf") ?? "",
      },
    });
  }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const pick = (list) => list[Math.floor(Math.random() * list.length)];

async function person(i, until) {
  const c = new Client(`198.51.100.${i + 1}`);
  await c.call("GET /api/health", "/api/health");
  const login = await c.write(
    "POST /api/auth/login",
    "/api/auth/login",
    "POST",
    {
      username: accounts[i % accounts.length],
      password,
    },
  );
  if (!login) return;
  // Живые обновления – одно соединение на человека, как у открытой вкладки.
  const live = new AbortController();
  let refreshing = false;
  fetch(base + "/api/live", {
    headers: c.headers({ Accept: "text/event-stream" }),
    signal: live.signal,
  })
    .then(async (res) => {
      note("SSE /api/live (открыть)", 0, res.ok);
      // Как lib/live.ts: на каждое изменение у кого-то – перечитать себя, предметы и «Сегодня».
      for await (const chunk of res.body) {
        if (!String(Buffer.from(chunk)).includes("event:change")) continue;
        if (refreshing || Date.now() >= until) continue;
        refreshing = true;
        Promise.all([
          c.call("GET /api/me (живое)", "/api/me"),
          c.call("GET /api/subjects (живое)", "/api/subjects"),
          c.call("GET /api/today (живое)", "/api/today"),
        ]).finally(() => (refreshing = false));
      }
    })
    .catch(() => {});
  let homework = [];
  let news = [];
  while (Date.now() < until) {
    await c.call("GET /api/me", "/api/me");
    await c.call("GET /api/today", "/api/today");
    await c.call("GET /api/subjects", "/api/subjects");
    news = (await c.call("GET /api/news", "/api/news")) ?? news;
    homework = (await c.call("GET /api/homework", "/api/homework")) ?? homework;
    const now = Date.now();
    await c.call(
      "GET /api/schedule",
      `/api/schedule?from=${now - 7 * 864e5}&to=${now + 28 * 864e5}`,
    );
    const hw = Array.isArray(homework) ? homework : (homework?.items ?? []);
    if (hw.length && Math.random() < 0.5) {
      const h = pick(hw);
      await c.write(
        "PUT /api/homework/{id}/done",
        `/api/homework/${h.id}/done`,
        "PUT",
        {
          value: Math.random() < 0.5,
        },
      );
    }
    const ns = Array.isArray(news) ? news : (news?.items ?? []);
    if (ns.length && Math.random() < 0.2) {
      const n = pick(ns);
      await c.write(
        "POST /api/news/{id}/comments",
        `/api/news/${n.id}/comments`,
        "POST",
        {
          body: `Нагрузочный комментарий ${i}`,
        },
      );
    }
    // Человек читает страницу 1–4 секунды.
    await sleep(1000 + Math.random() * 3000);
  }
  live.abort();
}

const pct = (sorted, p) =>
  sorted[Math.min(sorted.length - 1, Math.floor((sorted.length * p) / 100))];

const until = Date.now() + seconds * 1000;
const started = performance.now();
await Promise.all(
  Array.from({ length: people }, (_, i) =>
    sleep(i * 100).then(() => person(i, until)),
  ),
);
const total = (performance.now() - started) / 1000;

let requests = 0;
let errors = 0;
console.log(`\n${people} человек, ${total.toFixed(0)} с\n`);
console.log(
  "запрос".padEnd(34),
  "всего".padStart(6),
  "ошибок".padStart(7),
  "медиана".padStart(9),
  "p95".padStart(7),
  "p99".padStart(7),
);
for (const [name, s] of [...stats].sort()) {
  const t = [...s.times].sort((a, b) => a - b);
  requests += t.length;
  errors += s.errors;
  console.log(
    name.padEnd(34),
    String(t.length).padStart(6),
    String(s.errors).padStart(7),
    `${pct(t, 50).toFixed(0)} мс`.padStart(9),
    `${pct(t, 95).toFixed(0)}`.padStart(7),
    `${pct(t, 99).toFixed(0)}`.padStart(7),
  );
}
console.log(
  `\nВсего запросов: ${requests}, ошибок: ${errors}, ${(requests / total).toFixed(1)} в секунду`,
);
process.exit(errors > 0 ? 1 : 0);
