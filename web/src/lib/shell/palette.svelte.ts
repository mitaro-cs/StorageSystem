/** Открыта ли палитра команд; начальный текст запроса. */
export const palette = $state({ open: false, query: '' });

export function openPalette(query = '') {
	palette.query = query;
	palette.open = true;
}
