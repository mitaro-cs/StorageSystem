import type { Material } from '$lib/types';

/**
 * Разделение материалов по типу (0.9.3): фильтр «Конспекты / PDF / Документы / …» над списком.
 * Конспекты — сообщения и файлы Markdown (.md): их можно открыть страницей и скачать в Word.
 */
export type MaterialType =
	'notes' | 'pdf' | 'doc' | 'slides' | 'sheet' | 'image' | 'media' | 'archive' | 'link' | 'other';

export const TYPE_LABELS: Record<MaterialType, string> = {
	notes: 'Конспекты',
	pdf: 'PDF',
	doc: 'Документы',
	slides: 'Презентации',
	sheet: 'Таблицы',
	image: 'Картинки',
	media: 'Видео и аудио',
	archive: 'Архивы',
	link: 'Ссылки',
	other: 'Другое'
};

const ORDER = Object.keys(TYPE_LABELS) as MaterialType[];

export function isMarkdown(mime: string | null | undefined, name = ''): boolean {
	return /^text\/(x-web-)?markdown$/.test(mime ?? '') || /\.(md|markdown)$/i.test(name);
}

export function materialType(m: Pick<Material, 'kind' | 'file'>): MaterialType {
	if (m.kind === 'note') return 'notes';
	if (m.kind === 'link') return 'link';
	const mime = (m.file?.mime ?? '').toLowerCase();
	const name = m.file?.name ?? '';
	if (isMarkdown(mime, name)) return 'notes';
	if (mime === 'application/pdf' || /\.pdf$/i.test(name)) return 'pdf';
	if (/presentation|powerpoint|keynote/.test(mime) || /\.(pptx?|odp|key)$/i.test(name))
		return 'slides';
	if (/sheet|excel|csv/.test(mime) || /\.(xlsx?|ods|csv|tsv)$/i.test(name)) return 'sheet';
	if (mime.startsWith('image/')) return 'image';
	if (mime.startsWith('video/') || mime.startsWith('audio/')) return 'media';
	if (/zip|rar|7z|tar|gzip/.test(mime)) return 'archive';
	if (
		/word|document|rtf|opendocument\.text|^text\//.test(mime) ||
		/\.(docx?|odt|rtf|txt)$/i.test(name)
	)
		return 'doc';
	return 'other';
}

/** Какие типы есть в списке — по порядку, с числом. */
export function typesIn(
	list: Pick<Material, 'kind' | 'file'>[]
): { type: MaterialType; count: number }[] {
	const counts = new Map<MaterialType, number>();
	for (const m of list) {
		const t = materialType(m);
		counts.set(t, (counts.get(t) ?? 0) + 1);
	}
	return ORDER.filter((t) => counts.has(t)).map((type) => ({ type, count: counts.get(type)! }));
}
