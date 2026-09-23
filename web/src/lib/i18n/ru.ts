/** Строки интерфейса. Для перевода добавьте en.ts с той же структурой. */
export const ru = {
	app: 'groupbase',
	nav: {
		today: 'Сегодня',
		news: 'Новости',
		homework: 'ДЗ',
		homeworkLong: 'Домашние задания',
		materials: 'Материалы',
		subjects: 'Предметы',
		members: 'Участники',
		search: 'Поиск',
		notifications: 'Уведомления',
		profile: 'Профиль',
		settings: 'Настройки',
		allGroups: 'Все группы',
		pinned: 'Закреплённые',
		collapse: 'Свернуть панель',
		expand: 'Развернуть панель'
	},
	roles: {
		admin: 'Администратор',
		moderator: 'Модератор',
		headman: 'Староста',
		deputy: 'Зам старосты',
		student: 'Студент'
	},
	permissions: {
		create_accounts: 'Создавать аккаунты',
		manage_subjects: 'Создавать и редактировать предметы',
		suggest_materials: 'Предлагать материалы (на премодерацию)'
	} as Record<string, string>,
	common: {
		save: 'Сохранить',
		cancel: 'Отмена',
		delete: 'Удалить',
		edit: 'Изменить',
		create: 'Создать',
		close: 'Закрыть',
		copy: 'Скопировать',
		copied: 'Скопировано',
		loading: 'Загрузка…',
		more: 'Ещё',
		hide: 'Скрыть',
		unhide: 'Вернуть',
		hidden: 'Скрыто',
		deletedUser: 'удалённый пользователь',
		retry: 'Повторить',
		back: 'Назад',
		shared: 'общий'
	}
};

export const t = ru;
