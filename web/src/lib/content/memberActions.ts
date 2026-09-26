import { del, post, put } from '$lib/api';
import { can, isAdmin, session } from '$lib/session.svelte';
import { toast, toastError } from '$lib/toasts.svelte';
import type { GroupRole, InstanceRole, Member } from '$lib/types';
import type { MenuItem } from '$lib/ui/Menu.svelte';
import { ask } from '$lib/ui/ask.svelte';

export interface MemberHooks {
	/** Список изменился — перечитать. */
	reload: () => void;
	/** Показать одноразовую ссылку сброса пароля. */
	resetLink: (path: string, m: Member) => void;
	/** Своя роль изменилась — перечитать сессию. */
	selfChanged?: () => void;
}

/**
 * Действия над участником в меню «…». Администратор меняет роли всем, в том числе себе (последнего
 * администратора снять нельзя — сервер откажет); староста и зам — только другим и в пределах
 * своих прав.
 */
export function memberActions(m: Member, groupId: number, hooks: MemberHooks): MenuItem[] {
	const self = m.userId === session.me?.user.id;
	const admin = isAdmin();
	const out: MenuItem[] = [];
	const base = `/api/groups/${groupId}/members/${m.userId}`;

	async function act(fn: () => Promise<unknown>, message: string) {
		try {
			await fn();
			toast(message, 'ok');
			hooks.reload();
			if (self) hooks.selfChanged?.();
		} catch (e) {
			toastError(e);
		}
	}

	const setRole = (role: GroupRole) => () =>
		act(() => put(`${base}/role`, { role }), 'Роль в группе изменена');
	if (!self || admin) {
		if (m.role !== 'headman' && can('assign_headman', groupId))
			out.push({ label: 'Назначить старостой', onclick: setRole('headman') });
		if (m.role !== 'deputy' && can('assign_deputy', groupId) && (m.role !== 'headman' || admin))
			out.push({ label: 'Сделать замом старосты', onclick: setRole('deputy') });
		if (m.role !== 'student' && (m.role === 'deputy' ? can('assign_deputy', groupId) : admin))
			out.push({ label: 'Сделать студентом', onclick: setRole('student') });
	}

	if (admin) {
		const setSite = (role: InstanceRole | null, message: string, question?: string) => async () => {
			if (question && !(await ask(question))) return;
			return act(() => put(`/api/admin/users/${m.userId}/instance-role`, { role }), message);
		};
		if (m.instanceRole !== 'admin')
			out.push({
				label: 'Сделать администратором',
				onclick: setSite(
					'admin',
					self ? 'Роль изменена' : 'Назначен администратором. Ему нужно войти заново',
					self
						? undefined
						: `Администратор управляет всем сайтом: группами, ролями, сервером. Назначить ${m.displayName}?`
				)
			});
		if (m.instanceRole !== 'moderator')
			out.push({
				label: 'Сделать модератором',
				onclick: setSite(
					'moderator',
					self ? 'Теперь вы модератор' : 'Назначен модератором',
					self ? 'Снять с себя роль администратора и стать модератором?' : undefined
				)
			});
		if (m.instanceRole)
			out.push({
				label: m.instanceRole === 'admin' ? 'Снять роль администратора' : 'Снять роль модератора',
				danger: self,
				onclick: setSite(
					null,
					'Роль на сайте снята',
					self ? 'Снять роль с себя? Управлять сайтом вы больше не сможете.' : undefined
				)
			});
	}

	if (self) return out;

	if (can('reset_passwords', groupId) && (m.role !== 'headman' || admin))
		out.push({
			label: 'Ссылка для сброса пароля',
			onclick: async () => {
				try {
					const r = await post<{ path: string }>(`${base}/reset-link`);
					hooks.resetLink(r.path, m);
				} catch (e) {
					toastError(e);
				}
			}
		});
	if (can('block_users', groupId) && (m.role !== 'headman' || admin)) {
		out.push(
			m.status === 'blocked'
				? {
						label: 'Разблокировать',
						onclick: () => act(() => post(`${base}/unblock`), 'Разблокирован')
					}
				: {
						label: 'Заблокировать',
						danger: true,
						onclick: async () => {
							if (
								await ask(`Заблокировать ${m.displayName}? Все сессии будут закрыты.`, {
									ok: 'Заблокировать',
									danger: true
								})
							)
								await act(() => post(`${base}/block`), 'Заблокирован');
						}
					}
		);
		out.push({
			label: 'Исключить из группы',
			danger: true,
			onclick: async () => {
				if (await ask(`Исключить ${m.displayName} из группы?`, { ok: 'Исключить', danger: true }))
					await act(() => del(base), 'Исключён');
			}
		});
	}
	return out;
}
