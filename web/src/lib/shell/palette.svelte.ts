/**
 * Открыта ли палитра команд; начальный текст запроса; окно «Горячие клавиши»; окно «Загрузить
 * файл» (с главной и из палитры — одно на всё приложение, в макете).
 */
export const palette = $state({ open: false, query: '', help: false, upload: false });

export function openPalette(query = '') {
	palette.query = query;
	palette.open = true;
}

/** Подсказка по сочетаниям клавиш — из палитры («?») или клавишей «?» на странице. */
export function openHelp() {
	palette.open = false;
	palette.query = '';
	palette.help = true;
}
