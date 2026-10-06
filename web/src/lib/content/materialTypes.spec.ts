import { describe, expect, it } from 'vitest';
import { materialType, typesIn } from './materialTypes';

const file = (mime: string, name: string) =>
	({ kind: 'file', file: { id: 1, name, mime, size: 1 } }) as never;

describe('materialType — разделение по типам', () => {
	it('конспекты: сообщения и Markdown', () => {
		expect(materialType({ kind: 'note', file: null })).toBe('notes');
		expect(materialType(file('text/x-web-markdown', 'Лекция.md'))).toBe('notes');
		expect(materialType(file('text/plain', 'конспект.MD'))).toBe('notes');
	});

	it('документы, презентации, таблицы, PDF', () => {
		expect(materialType(file('application/pdf', 'a.pdf'))).toBe('pdf');
		expect(
			materialType(
				file('application/vnd.openxmlformats-officedocument.wordprocessingml.document', 'a.docx')
			)
		).toBe('doc');
		expect(
			materialType(
				file('application/vnd.openxmlformats-officedocument.presentationml.presentation', 'a.pptx')
			)
		).toBe('slides');
		expect(materialType(file('text/csv', 'a.csv'))).toBe('sheet');
		expect(materialType({ kind: 'link', file: null })).toBe('link');
	});

	it('typesIn — по порядку и с числом', () => {
		expect(
			typesIn([
				file('application/pdf', 'a.pdf'),
				{ kind: 'note', file: null } as never,
				file('application/pdf', 'b.pdf')
			])
		).toEqual([
			{ type: 'notes', count: 1 },
			{ type: 'pdf', count: 2 }
		]);
	});
});
