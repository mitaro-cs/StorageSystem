import { describe, expect, it } from 'vitest';
import { canPreview, fileKind } from './fileKinds';

describe('как открыть файл', () => {
	it.each([
		['application/pdf', 'Лекция.pdf', 'pdf'],
		['', 'скан.PDF', 'pdf'],
		['image/jpeg', 'фото.jpg', 'image'],
		['image/png', 'схема.png', 'image'],
		['image/svg+xml', 'logo.svg', 'other'],
		['video/mp4', 'разбор.mp4', 'video'],
		['audio/mpeg', 'лекция.mp3', 'audio'],
		['text/plain', 'readme.txt', 'text'],
		['application/octet-stream', 'lab.py', 'text'],
		['text/html', 'page.html', 'text'],
		['application/vnd.openxmlformats-officedocument.wordprocessingml.document', 'ДЗ.docx', 'other'],
		['application/zip', 'архив.zip', 'other']
	])('%s «%s» → %s', (mime, name, kind) => {
		expect(fileKind(mime, name)).toBe(kind);
	});

	it('большой текст не показываем', () => {
		expect(canPreview('text/plain', 'log.txt', 100)).toBe(true);
		expect(canPreview('text/plain', 'log.txt', 50 * 1024 * 1024)).toBe(false);
		expect(canPreview('application/zip', 'a.zip', 1)).toBe(false);
	});
});
