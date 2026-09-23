import { get } from './api';
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

/** Право в конкретной группе или (без группы) хотя бы в одной. */
export function can(perm: Permission, groupId?: number | null): boolean {
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
	return session.me?.user.instanceRole === 'admin';
}
