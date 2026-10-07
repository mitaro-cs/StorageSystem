import type { HomeworkKind } from './content/kinds';
export type Permission =
	| 'manage_instance'
	| 'assign_moderator'
	| 'assign_headman'
	| 'assign_deputy'
	| 'create_accounts'
	| 'create_invites'
	| 'block_users'
	| 'reset_passwords'
	| 'manage_subjects'
	| 'share_subjects'
	| 'publish_news'
	| 'publish_homework'
	| 'upload_materials'
	| 'suggest_materials'
	| 'manage_schedule'
	| 'moderate_content'
	| 'comment'
	| 'view_audit'
	| 'manage_permissions'
	| 'view_usernames'
	| 'export_group'
	| 'view_group';

export type GroupRole = 'headman' | 'deputy' | 'student';
export type InstanceRole = 'admin' | 'moderator';

export interface MeGroup {
	id: number;
	slug: string;
	name: string;
	university: string;
	course: number | null;
	avatar: string | null;
	archived: boolean;
	role: GroupRole | null;
	permissions: Permission[];
	/** Закреплённые чаты группы в Telegram. */
	chats: GroupChat[];
	/** Сессия: первый и последний день (полночь, мс); null – даты не заданы. */
	session: { from: number; to: number } | null;
	/**
	 * Кнопка «Сессия» в меню: auto – около сессии (по датам), show – всегда, hide – никогда. У копии
	 * данных от прежних версий поля нет – считается auto.
	 */
	sessionNav?: 'auto' | 'show' | 'hide';
}

export interface GroupChat {
	id: number;
	title: string;
	/** Всегда https://t.me/… */
	url: string;
}

export interface Me {
	user: {
		id: number;
		username: string;
		displayName: string;
		avatar: string | null;
		instanceRole: InstanceRole | null;
		totpEnabled: boolean;
		/** Режим управления: false – кнопки администратора и старосты скрыты. */
		manageMode: boolean;
		/** Тур по сайту уже пройден (у серверов до 0.4.8 поля нет – не показываем). */
		onboarded?: boolean;
		/** Закрытые подсказки разделов; «*» – все (с 0.6, у старых серверов поля нет). */
		tips?: string[];
		/** Оформление для всех устройств (lib/looksSync.ts); null – не выбирал, нет поля – сервер до 0.6. */
		appearance?: Record<string, unknown> | null;
		/** Своя картинка фона на сервере: /api/avatars/<id>-1920.webp. */
		background?: string | null;
		/** Принял действующие правила сайта; false – спросить согласие. */
		termsAccepted?: boolean;
	};
	restriction: 'password_change_required' | 'totp_setup_required' | null;
	instance: {
		name: string;
		mode: 'single' | 'multi';
		version: string;
		requireStaffTotp: boolean;
		/** Адрес сайта для участников (ссылки, QR); null – адрес из браузера. */
		publicUrl: string | null;
		/** Сервер работает в приложении хоста на его компьютере. */
		desktop: boolean;
	};
	groups: MeGroup[];
	permissions: Permission[];
	/** Это окно приложения хоста на его компьютере. */
	hostWindow: boolean;
}

export interface Person {
	id: number;
	displayName: string;
	avatar: string | null;
	deleted: boolean;
}

export interface GroupRef {
	id: number;
	name: string;
}

export interface SubjectRef {
	id: number;
	name: string;
	color: string;
}

export interface Subject {
	id: number;
	name: string;
	teacher: string;
	color: string;
	avatar: string | null;
	/** Иконка из набора ($lib/subjectIcons); null – подбирается по названию. */
	icon: string | null;
	/** Фон карточки – широкая картинка вместо иконки (у серверов до 0.4.8 поля нет). */
	cover?: string | null;
	/** Чат предмета в Telegram (https://t.me/…). */
	chatUrl: string | null;
	/** Свой преподаватель у лекций, практики, семинаров, лабораторных (0.9.6; у старых серверов нет). */
	teachers?: Partial<Record<'lecture' | 'practice' | 'seminar' | 'lab', string>>;
	archived: boolean;
	pinned: boolean;
	/** Предмет этого человека; false – скрыл у себя как предмет другой подгруппы (с 0.4.10). */
	mine?: boolean;
	groups: GroupRef[];
	can: { edit: boolean; share: boolean };
	/** Пар в расписании (с 0.4.12): есть – у предмета вкладка «Пары». */
	lessons?: number;
}

export interface ItemCan {
	edit: boolean;
	delete: boolean;
	hide: boolean;
}

export interface NewsItem {
	id: number;
	title: string;
	bodyMd: string;
	bodyHtml: string;
	pinned: boolean;
	urgent: boolean;
	hidden: boolean;
	createdAt: number;
	updatedAt: number;
	author: Person;
	subject: SubjectRef | null;
	groups: GroupRef[];
	comments: number;
	/** Фото и файлы (у серверов до 0.4.10 поля нет). */
	attachments?: FileInfo[];
	can: ItemCan;
	/** Создано без сети и ещё не отправлено на сервер. */
	pending?: boolean;
}

export interface NewsPage {
	pinned: NewsItem[];
	items: NewsItem[];
	next: number | null;
}

export interface Homework {
	id: number;
	title: string;
	bodyMd: string;
	bodyHtml: string;
	dueAt: number;
	/** Сложность: 1 – легко, 2 – средне, 3 – сложно; null – не указана. */
	difficulty: number | null;
	/** Тип: домашнее, лабораторная, контрольная, зачёт, экзамен. */
	kind: HomeworkKind;
	/** Аудитория или ссылка – для зачёта и экзамена; '' – не указано. */
	place: string;
	done: boolean;
	hidden: boolean;
	createdAt: number;
	updatedAt: number;
	author: Person;
	subject: SubjectRef;
	groups: GroupRef[];
	comments: number;
	attachments: FileInfo[];
	can: ItemCan;
	/** Пара из расписания, к которой задание (с 0.4.12); null – просто срок. */
	lesson?: LessonRef | null;
	/** Тест: когда откроется (закроется – в срок, dueAt); null – открыт сразу. */
	opensAt?: number | null;
	/** Создано без сети и ещё не отправлено на сервер. */
	pending?: boolean;
}

export type LessonKind =
	'lecture' | 'practice' | 'seminar' | 'lab' | 'consult' | 'credit' | 'exam' | 'other';

/** Пара из расписания группы. */
export interface Lesson {
	id: number;
	groupId: number;
	/** null – пара без предмета (классный час, кураторский час). */
	subject: SubjectRef | null;
	/** Название из файла календаря или своё; показывается, если нет предмета. */
	title: string;
	kind: LessonKind;
	startsAt: number;
	endsAt: number;
	place: string;
	teacher: string;
	/** Тема занятия или заметка. */
	note: string;
	/** Сколько заданий и материалов к этой паре. */
	homework: number;
	materials: number;
	/** Пары не было – отметил староста. */
	cancelled?: boolean;
	can: { edit: boolean };
}

export interface LessonRef {
	id: number;
	startsAt: number;
	endsAt: number;
	kind: LessonKind;
	place: string;
}

export interface LessonNeighbor {
	id: number;
	startsAt: number;
	kind: LessonKind;
}

export interface LessonDetail {
	lesson: Lesson;
	homework: Homework[];
	materials: Material[];
	prev: LessonNeighbor | null;
	next: LessonNeighbor | null;
}

/** Что в файле календаря – перед загрузкой. */
export interface SchedulePreview {
	lessons: number;
	from: number;
	to: number;
	titles: {
		key: string;
		name: string;
		count: number;
		kinds: Partial<Record<LessonKind, number>>;
		subjectId: number | null;
		teacher: string;
		places: string[];
	}[];
	allDay: number;
	cancelled: number;
	unsupported: number;
	past: number;
	existing: number;
	replaced: number;
}

export interface Today {
	upcoming: Homework[];
	overdue: Homework[];
	pinned: NewsItem[];
	news: NewsItem[];
	/** Зачёты и экзамены: недавние и будущие – для карточки сессии. */
	exams: Homework[];
	/** Пары сегодня и завтра (у серверов до 0.4.12 и в старой копии поля нет). */
	lessons?: Lesson[];
}

export interface Comment {
	id: number;
	author: Person;
	bodyHtml: string;
	createdAt: number;
	hidden: boolean;
	canDelete: boolean;
	/** Скрыть и вернуть может модератор (у серверов до 0.4.8 поля нет). */
	canHide?: boolean;
	/** Создано без сети и ещё не отправлено на сервер. */
	pending?: boolean;
}

export interface Member {
	userId: number;
	/** Только для старосты и администратора (право view_usernames), остальным – null. */
	username: string | null;
	displayName: string;
	avatar: string | null;
	status: 'pending' | 'active' | 'blocked' | 'deleted';
	role: GroupRole;
	/** Роль на всём сайте: администратор или модератор. */
	instanceRole: InstanceRole | null;
	joinedAt: number;
}

export interface Invite {
	id: number;
	groupId: number;
	role: GroupRole;
	maxUses: number | null;
	uses: number;
	expiresAt: number;
	note: string;
	createdBy: number | null;
	createdAt: number;
	revokedAt: number | null;
}

export interface CreatedAccount {
	userId: number;
	username: string;
	displayName: string;
	activationPath: string | null;
	temporaryPassword: string | null;
}

export interface LinkRequest {
	id: number;
	subjectId: number;
	subjectName: string;
	fromGroupId: number;
	fromGroupName: string;
	toGroupId: number;
	toGroupName: string;
	requestedBy: number | null;
	status: string;
	createdAt: number;
}

export interface PermissionCell {
	role: GroupRole;
	permission: Permission;
	allowed: boolean;
	overridden?: boolean;
}

export interface AuditEntry {
	id: number;
	at: number;
	actorId: number | null;
	actorName: string | null;
	groupId: number | null;
	action: string;
	targetType: string | null;
	targetId: number | null;
	details: string | null;
	ip: string | null;
}

export interface FileInfo {
	id: number;
	name: string;
	mime: string;
	size: number;
	/** Создано без сети и ещё не отправлено на сервер. */
	pending?: boolean;
}

export interface Material {
	id: number;
	subjectId: number;
	subjectName: string;
	subjectColor: string;
	folderId: number | null;
	/** note – сообщение: текст в description (с 0.6). */
	kind: 'file' | 'link' | 'note';
	title: string;
	description: string;
	url: string | null;
	file: FileInfo | null;
	author: Person;
	status: 'published' | 'pending' | 'rejected';
	hidden: boolean;
	createdAt: number;
	comments: number;
	/** pin – можно закрепить сверху (с 0.6). */
	can: { edit: boolean; delete: boolean; moderate: boolean; pin?: boolean };
	/** Пара из расписания, к которой материал (с 0.4.12). */
	lessonId?: number | null;
	/** Закреплён сверху (с 0.6); null – нет. */
	pinnedAt?: number | null;
	/** Создано без сети и ещё не отправлено на сервер. */
	pending?: boolean;
}

export interface Folder {
	id: number;
	parentId: number | null;
	name: string;
	count: number;
}

export interface MaterialListing {
	path: { id: number; name: string }[];
	folders: Folder[];
	materials: Material[];
	canUpload: boolean;
	canSuggest: boolean;
}

export interface SearchSegment {
	text: string;
	hit: boolean;
}

export type SearchKind = 'homework' | 'news' | 'material' | 'subject';

export interface SearchHit {
	kind: SearchKind;
	id: number;
	title: SearchSegment[];
	snippet: SearchSegment[];
	subject: SubjectRef | null;
	date: number | null;
	url: string;
	hidden: boolean;
}

export interface SearchResult {
	query: string;
	items: SearchHit[];
	counts: Partial<Record<SearchKind, number>>;
}

export interface NotificationItem {
	id: number;
	kind: 'homework' | 'news' | 'material' | 'material_pending' | 'reminder' | 'digest' | 'test';
	title: string;
	body: string;
	url: string;
	createdAt: number;
	read: boolean;
}

export interface NotificationPage {
	items: NotificationItem[];
	unread: number;
	next: number | null;
}

export interface NotificationPrefs {
	homework: boolean;
	news: 'all' | 'urgent' | 'none';
	materials: boolean;
	reminders: boolean;
	digest: boolean;
	digestAt: number;
}

export interface PushDevice {
	id: number;
	device: string;
	createdAt: number;
	lastOkAt: number | null;
}

export interface NotificationSettings {
	prefs: NotificationPrefs;
	devices: PushDevice[];
	pushEnabled: boolean;
	publicKey: string;
}

// ---------- модерация ----------

export type ModType = 'post' | 'homework' | 'material' | 'comment';

/** Новость, задание, материал или комментарий в «Модерации». */
export interface ModTarget {
	type: ModType;
	id: number;
	/** Заголовок; у комментария – того, к чему он. */
	title: string;
	/** Начало текста одной строкой. */
	text: string;
	author: Person;
	createdAt: number;
	hidden: boolean;
	href: string;
	groups: number[];
	/** У комментария – к чему он. */
	parentType: Exclude<ModType, 'comment'> | null;
}

export interface ModReport {
	target: ModTarget;
	count: number;
	reasons: string[];
	lastAt: number;
}

export interface ModSummary {
	pending: number;
	reports: number;
}

export interface ModLogEntry {
	id: number;
	at: number;
	actorName: string | null;
	action: string;
	targetType: ModType | null;
	targetId: number | null;
	title: string | null;
}
