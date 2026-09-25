// Заставка, пока запускается сервер группы. Статус присылает оболочка (событие «status»).
const { invoke } = window.__TAURI__.core;
const { listen } = window.__TAURI__.event;

const statusEl = document.getElementById('status');
const details = document.getElementById('details');
const more = document.getElementById('more');
const actions = document.getElementById('actions');
const progress = document.getElementById('progress');
const bar = document.getElementById('bar');
const codeEl = document.getElementById('code');
const advice = document.getElementById('advice');

/** Что делать при каждой ошибке. Те же коды — в docs/desktop.md («Коды ошибок»). */
const ADVICE = {
	'GB-200':
		'Нажмите «Перезапустить». Если ошибка повторится, откройте журналы и пришлите код и файлы groupbase.log и java.log тому, кто помогает с groupbase.',
	'GB-201': 'Нажмите «Перезапустить» — groupbase выберет свободный порт.',
	'GB-202':
		'groupbase уже работает — посмотрите значок в строке меню (на Windows — в трее). Закройте лишнюю копию или перезагрузите компьютер.',
	'GB-203':
		'Нажмите «Перезапустить». Если повторяется — откройте журналы: причина в конце файла groupbase.log.',
	'GB-204':
		'Файлы приложения повреждены. Скачайте установщик со страницы выпуска и установите поверх — данные сохранятся.',
	'GB-205':
		'Система не дала запустить сервер. Перезагрузите компьютер; если не поможет — установите groupbase поверх (данные сохранятся).',
	'GB-206':
		'Восстановите данные из резервной копии: папка groupbase-backups или команда groupbase restore (см. инструкцию для хоста).',
	'GB-207': 'Освободите место на диске (хотя бы 1 ГБ) и нажмите «Перезапустить».',
	'GB-208':
		'Эта копия не подошла: выберите другую или обновите приложение, если копия сделана более новой версией.',
	'GB-209': 'Закройте тяжёлые программы и нажмите «Перезапустить».'
};

/** «Код ошибки GB-203» — без innerHTML: код приходит от процесса сервера. */
function label(c) {
	const b = document.createElement('b');
	b.textContent = c;
	codeEl.replaceChildren('Код ошибки ', b);
}

function show({ text, error, code }) {
	// Первая строка — суть, остальное (хвост журнала) — в «Подробностях».
	const [head, ...rest] = String(text || '').split('\n\n');
	statusEl.textContent = head || 'Запускаем сервер группы…';
	details.textContent = rest.join('\n\n');
	more.hidden = !error || rest.length === 0;
	actions.hidden = !error;
	document.body.classList.toggle('error', !!error);
	const c = error ? code || 'GB-200' : '';
	codeEl.hidden = !c;
	if (c) label(c);
	codeEl.dataset.code = c;
	advice.hidden = !c;
	advice.textContent = ADVICE[c] || ADVICE['GB-200'];
	// «Скачиваем обновление… 45%» — показываем долю полосой.
	const pct = !error && /(\d{1,3})%/.exec(head || '');
	progress.hidden = !pct;
	if (pct) bar.style.width = Math.min(100, Number(pct[1])) + '%';
}

codeEl.addEventListener('click', async () => {
	const c = codeEl.dataset.code;
	try {
		await navigator.clipboard.writeText(c);
		codeEl.textContent = 'Скопировано';
		setTimeout(() => label(c), 1400);
	} catch {
		/* буфер обмена недоступен — код и так на экране */
	}
});
document.getElementById('restart').addEventListener('click', () => {
	show({ text: 'Запускаем сервер группы…', error: false });
	invoke('restart_server');
});
document.getElementById('logs').addEventListener('click', () => invoke('open_logs'));

listen('status', (e) => show(e.payload));
invoke('current_status').then(show);
