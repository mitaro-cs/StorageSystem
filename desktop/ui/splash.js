// Заставка, пока запускается сервер группы. Статус присылает оболочка (событие «status»).
const { invoke } = window.__TAURI__.core;
const { listen } = window.__TAURI__.event;

const statusEl = document.getElementById('status');
const details = document.getElementById('details');
const actions = document.getElementById('actions');
const progress = document.getElementById('progress');
const bar = document.getElementById('bar');

function show({ text, error }) {
	// Первая строка — суть, остальное (хвост журнала) — в блоке подробностей.
	const [head, ...rest] = String(text || '').split('\n\n');
	statusEl.textContent = head || 'Запускаем сервер группы…';
	details.textContent = rest.join('\n\n');
	details.hidden = !error || rest.length === 0;
	actions.hidden = !error;
	document.body.classList.toggle('error', !!error);
	// «Скачиваем обновление… 45%» — показываем долю полосой.
	const pct = !error && /(\d{1,3})%/.exec(head || '');
	progress.hidden = !pct;
	if (pct) bar.style.width = Math.min(100, Number(pct[1])) + '%';
}

document.getElementById('restart').addEventListener('click', () => {
	show({ text: 'Запускаем сервер группы…', error: false });
	invoke('restart_server');
});
document.getElementById('logs').addEventListener('click', () => invoke('open_logs'));

listen('status', (e) => show(e.payload));
invoke('current_status').then(show);
