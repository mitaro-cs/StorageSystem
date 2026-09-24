import { describe, expect, it } from 'vitest';
import { ALL_ICON_KEYS, FALLBACK_ICON, guessIcon, iconExists, subjectIcon } from './subjectIcons';

describe('иконки предметов', () => {
	it('ключи подходят под проверку сервера и не повторяются', () => {
		for (const k of ALL_ICON_KEYS) expect(k).toMatch(/^[a-z0-9-]{1,32}$/);
		expect(new Set(ALL_ICON_KEYS).size).toBe(ALL_ICON_KEYS.length);
		expect(ALL_ICON_KEYS.length).toBeGreaterThanOrEqual(60);
	});

	it.each([
		['Математический анализ', 'sigma'],
		['Высшая математика', 'sigma'],
		['Линейная алгебра', 'sigma'],
		['Теория вероятностей и математическая статистика', 'chart-spline'],
		['Дифференциальные уравнения', 'function'],
		['Дискретная математика', 'binary'],
		['Физика', 'atom'],
		['Химия', 'flask'],
		['Программирование на Python', 'code'],
		['Основы алгоритмизации', 'code'],
		['Базы данных', 'database'],
		['Сети связи и системы коммутации', 'network'],
		['Компьютерные сети', 'network'],
		['Беспроводные сети Wi-Fi', 'wifi'],
		['Спутниковые системы', 'satellite'],
		['Информационная безопасность', 'shield'],
		['Безопасность жизнедеятельности', 'heart-pulse'],
		['Иностранный язык', 'languages'],
		['Английский язык', 'languages'],
		['Русский язык и культура речи', 'feather'],
		['История России', 'scroll'],
		['Правоведение', 'scale'],
		['Экономика', 'coins'],
		['Менеджмент', 'briefcase'],
		['Физическая культура и спорт', 'dumbbell'],
		['Элективные дисциплины по физкультуре', 'dumbbell'],
		['Инженерная графика', 'pen-tool'],
		['Философия', 'lightbulb'],
		['Психология', 'brain'],
		['Электротехника', 'zap'],
		['Архитектура ЭВМ', 'cpu'],
		['Схемотехника', 'circuit'],
		['Операционные системы', 'terminal'],
		['Концепции современного естествознания', 'book']
	])('«%s» → %s', (name, key) => {
		expect(guessIcon(name).key).toBe(key);
	});

	it('сохранённый ключ важнее названия, неизвестный — подбирается', () => {
		expect(subjectIcon('rocket', 'Физика').key).toBe('rocket');
		expect(subjectIcon('nope', 'Физика').key).toBe('atom');
		expect(subjectIcon(null, 'Что-то своё').key).toBe(FALLBACK_ICON.key);
		expect(iconExists('atom')).toBe(true);
		expect(iconExists('Atom')).toBe(false);
	});
});
