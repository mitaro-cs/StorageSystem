import { get, patch } from './api';
import { rememberMe } from './offline/engine';
import type { Me, MeGroup, Permission } from './types';

const GROUP_KEY = 'gb-group';

/** Текущий пользователь и выбранная группа (null — «все группы» в режиме multi). */
export const session = $state<{ me: Me | null; groupId: number | null }>({
	me: null,
	groupId: null
});

export async function loadMe(f?: typeof fetch): Promise<Me> {
	const me = await get<Me>('/api/me', { fetch: f });
	session.me = me;
	restoreGroup(me);
	rememberMe(me);
	return me;
}

function restoreGroup(me: Me) {
	const ids = me.groups.map((g) => g.id);
	if (me.instance.mode === 'single' || ids.length === 1) {
		session.groupId = ids[0] ?? null;
		return;
	}
	let saved: number | null;
	try {
		const v = localStorage.getItem(GROUP_KEY);
		saved = v ? Number(v) : null;
	} catch {
		saved = null;
	}
	session.groupId = saved !== null && ids.includes(saved) ? saved : null;
}

export function selectGroup(id: number | null) {
	session.groupId = id;
	try {
		if (id === null) localStorage.removeItem(GROUP_KEY);
		else localStorage.setItem(GROUP_KEY, String(id));
	} catch {
		/* не запоминаем */
	}
}

export function groups(): MeGroup[] {
	return session.me?.groups ?? [];
}

export function currentGroup(): MeGroup | undefined {
	return groups().find((g) => g.id === session.groupId);
}

export function isMulti(): boolean {
	return (session.me?.instance.mode ?? 'single') === 'multi' && groups().length > 1;
}

/**
 * Права управления: их кнопки прячутся, когда режим управления выключен. Публиковать новости,
 * задания и материалы можно и без него.
 */
const MANAGE: ReadonlySet<Permission> = new Set<Permission>([
	'manage_instance',
	'assign_moderator',
	'assign_headman',
	'assign_deputy',
	'create_accounts',
	'create_invites',
	'block_users',
	'reset_passwords',
	'manage_subjects',
	'share_subjects',
	'moderate_content',
	'view_audit',
	'manage_permissions',
	'view_usernames',
	'export_group'
]);

/**
 * Режим управления включён. Выключать его может только хост — администратор сайта: у старосты,
 * замов и модераторов кнопки управления видны всегда (так решил владелец).
 */
export function manageMode(): boolean {
	const me = session.me;
	if (!me || me.user.instanceRole !== 'admin') return true;
	return me.user.manageMode;
}

/** Переключить режим управления: сразу на экране, сервер запоминает его для всех устройств. */
export async function setManageMode(on: boolean): Promise<void> {
	if (!session.me) return;
	session.me.user.manageMode = on;
	try {
		await patch('/api/me/preferences', { manageMode: on });
	} catch (e) {
		if (session.me) session.me.user.manageMode = !on;
		throw e;
	}
}

/** Переключатель «Режим управления» — только у администратора сайта (хоста). */
export function canToggleManage(): boolean {
	return session.me?.user.instanceRole === 'admin';
}

/** Помогает вести группу: права управления в группе или роль на сайте. */
export function canManage(): boolean {
	const me = session.me;
	if (!me) return false;
	if (me.user.instanceRole) return true;
	return [...me.permissions, ...me.groups.flatMap((g) => g.permissions)].some((p) => MANAGE.has(p));
}

/** Право в конкретной группе или (без группы) хотя бы в одной. */
export function can(perm: Permission, groupId?: number | null): boolean {
	if (!manageMode() && MANAGE.has(perm)) return false;
	return realCan(perm, groupId);
}

/** Право без учёта режима управления — для проверок, которые не про кнопки. */
export function realCan(perm: Permission, groupId?: number | null): boolean {
	const me = session.me;
	if (!me) return false;
	if (me.permissions.includes(perm)) return true;
	if (groupId !== undefined && groupId !== null) {
		return me.groups.find((g) => g.id === groupId)?.permissions.includes(perm) ?? false;
	}
	return me.groups.some((g) => g.permissions.includes(perm));
}

/** Группы, где есть право (для выбора адресатов публикации). */
export function groupsWith(perm: Permission): MeGroup[] {
	return groups().filter((g) => g.permissions.includes(perm) && !g.archived);
}

export function isAdmin(): boolean {
	return manageMode() && session.me?.user.instanceRole === 'admin';
}

/**
 * Права, у которых есть свой раздел в «Управлении». Журнал действий сюда не входит: модератору
 * пункт «Управление» не нужен — его журнал в «Модерации».
 */
const SETTINGS_PERMS: Permission[] = [
	'create_accounts',
	'create_invites',
	'block_users',
	'manage_permissions',
	'manage_subjects',
	'export_group'
];

/**
 * Есть ли чем управлять (пункт «Управление»). Всё своё — тема, уведомления, пароль — в личных
 * настройках (шестерёнка у имени), они есть у каждого.
 */
export function hasSettings(): boolean {
	if (!manageMode()) return false;
	return isAdmin() || SETTINGS_PERMS.some((p) => realCan(p));
}

/** Навигация без «Управления» для тех, кому там нечего делать. */
export function visibleNav<T extends { href: string }>(items: T[]): T[] {
	return items.filter(
		(i) =>
			(i.href !== '/settings' || hasSettings()) &&
			(i.href !== '/moderation' || can('moderate_content'))
	);
}
