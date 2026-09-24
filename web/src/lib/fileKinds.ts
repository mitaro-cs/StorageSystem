/** Как открыть файл внутри приложения. */
export type FileKind = 'image' | 'pdf' | 'video' | 'audio' | 'text' | 'other';

const IMAGES = new Set([
	'image/png',
	'image/jpeg',
	'image/gif',
	'image/webp',
	'image/avif',
	'image/bmp'
]);
const TEXT_MIME = /^(text\/(plain|markdown|csv|x-[a-z+-]+)|application\/(json|xml|x-sh|sql))$/;
const TEXT_EXT =
	/\.(txt|md|csv|tsv|json|xml|ya?ml|ini|toml|log|sql|py|ipynb|java|kt|c|h|cpp|hpp|cs|go|rs|js|ts|php|rb|swift|m|sh|bat|ps1|tex|html?|css)$/i;

export function fileKind(mime: string | null | undefined, name = ''): FileKind {
	const m = (mime ?? '').toLowerCase();
	if (m === 'application/pdf' || (!m && /\.pdf$/i.test(name))) return 'pdf';
	// SVG может содержать скрипт — показываем только как файл для скачивания.
	if (IMAGES.has(m)) return 'image';
	if (m.startsWith('video/')) return 'video';
	if (m.startsWith('audio/')) return 'audio';
	if (TEXT_MIME.test(m) || ((m === '' || m === 'application/octet-stream') && TEXT_EXT.test(name)))
		return 'text';
	if (m.startsWith('text/') && m !== 'text/html') return 'text';
	if (TEXT_EXT.test(name) && !/\.(docx?|pptx?|xlsx?)$/i.test(name)) return 'text';
	return 'other';
}

/** Текст больше этого не показываем — только скачать. */
export const TEXT_LIMIT = 2 * 1024 * 1024;

export function canPreview(mime: string | null | undefined, name = '', size = 0): boolean {
	const k = fileKind(mime, name);
	return k !== 'other' && !(k === 'text' && size > TEXT_LIMIT);
}
