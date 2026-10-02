/**
 * Тур по сайту (0.6): подсказки на настоящих кнопках. У шага — список селекторов, берётся первый
 * видимый: боковая панель на компьютере, нижняя панель на телефоне. Ничего не нашлось (например,
 * на «Сегодня» нет заданий) — подсказка по центру экрана, без подсветки.
 */
export interface TourStep {
	id: string;
	title: string;
	text: string;
	targets: string[];
}

export interface TourRole {
	/** Есть «Управление» (староста, зам, администратор). */
	manage: boolean;
	/** Есть «Модерация». */
	moderate: boolean;
	/** Телефон: нижняя панель вместо боковой. */
	phone: boolean;
}

export function tourSteps({ manage, moderate, phone }: TourRole): TourStep[] {
	const nav = (href: string) => [`.sidebar a[href="${href}"]`, `.bottom-nav a[href="${href}"]`];
	const steps: TourStep[] = [
		{
			id: 'today',
			title: 'Всё главное — на «Сегодня»',
			text: 'Что сдать на неделе, пары и свежие новости группы.',
			targets: nav('/')
		},
		{
			id: 'done',
			title: 'Сделали — отметьте',
			text: 'Кружок у задания: оно уйдёт вниз, и напоминаний о нём больше не будет.',
			targets: ['main .hw .toggle']
		},
		{
			id: 'schedule',
			title: 'Расписание пар',
			text: 'Вся неделя списком. К паре можно прикрепить задание и материалы.',
			targets: nav('/schedule')
		},
		{
			id: 'subjects',
			title: 'Всё по предметам',
			text: 'У каждого предмета свои задания, файлы и чат. Лекции открываются прямо здесь.',
			targets: nav('/subjects')
		},
		{
			id: 'search',
			title: 'Найти что угодно',
			text: phone
				? 'Задание, файл или раздел — поиск всегда наверху.'
				: 'Задание, файл или раздел. С клавиатуры — Ctrl K (на Mac ⌘K).',
			targets: ['.sidebar .finder', 'header.bar a[href="/search"]']
		}
	];
	if (manage)
		steps.push({
			id: 'manage',
			title: 'Вы ведёте группу',
			text: phone
				? 'Приглашения, права и семестр — в «Профиле» → «Управление».'
				: 'Приглашения, права и семестр — в «Управлении».',
			targets: [`.sidebar a[href="/settings"]`, `.bottom-nav a[href="/profile"]`]
		});
	if (moderate)
		steps.push({
			id: 'moderate',
			title: 'Модерация',
			text: 'Жалобы и записи на проверке ждут здесь. Кто пожаловался, не видно никому.',
			targets: [`.sidebar a[href="/moderation"]`, `.bottom-nav a[href="/profile"]`]
		});
	return steps;
}

/** Первый видимый элемент из списка селекторов. */
export function findTarget(selectors: string[]): HTMLElement | null {
	for (const s of selectors) {
		for (const el of document.querySelectorAll<HTMLElement>(s)) {
			const r = el.getBoundingClientRect();
			if (r.width > 0 && r.height > 0 && getComputedStyle(el).visibility !== 'hidden') return el;
		}
	}
	return null;
}

/** Где поставить карточку подсказки: под целью, если не влезает — над ней; всегда на экране. */
export function placeCard(
	target: { top: number; left: number; width: number; height: number } | null,
	card: { width: number; height: number },
	view: { width: number; height: number },
	gap = 14,
	margin = 12
): { top: number; left: number } {
	if (!target)
		return {
			top: Math.max(margin, (view.height - card.height) / 2),
			left: Math.max(margin, (view.width - card.width) / 2)
		};
	const clampX = (x: number) => Math.min(Math.max(margin, x), view.width - card.width - margin);
	const clampY = (y: number) => Math.min(Math.max(margin, y), view.height - card.height - margin);
	// Узкая цель в боковой панели — карточка справа от неё.
	const right = target.left + target.width + gap;
	if (
		target.left < view.width / 3 &&
		right + card.width + margin <= view.width &&
		view.width >= 900
	)
		return { top: clampY(target.top + target.height / 2 - card.height / 2), left: right };
	const below = target.top + target.height + gap;
	if (below + card.height + margin <= view.height)
		return { top: below, left: clampX(target.left + target.width / 2 - card.width / 2) };
	return {
		top: clampY(target.top - gap - card.height),
		left: clampX(target.left + target.width / 2 - card.width / 2)
	};
}
