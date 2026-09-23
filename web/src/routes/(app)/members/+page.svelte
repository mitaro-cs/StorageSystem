<script lang="ts">
	import { UserPlus } from '@lucide/svelte';
	import { del, post, put } from '$lib/api';
	import { absolute, canShare, copy, share } from '$lib/copy';
	import QrCode from '$lib/ui/QrCode.svelte';
	import { t } from '$lib/i18n/ru';
	import { can, currentGroup, groups, session } from '$lib/session.svelte';
	import { toast, toastError } from '$lib/toasts.svelte';
	import type { Member } from '$lib/types';
	import type { MenuItem } from '$lib/ui/Menu.svelte';
	import MemberList from '$lib/content/MemberList.svelte';
	import Modal from '$lib/ui/Modal.svelte';
	import Button from '$lib/ui/Button.svelte';

	let reload = $state(0);
	let link = $state<string | null>(null);

	const ids = $derived(session.groupId !== null ? [session.groupId] : groups().map((g) => g.id));
	const inviteGroup = $derived(
		currentGroup() ?? groups().find((g) => g.permissions.includes('create_invites'))
	);

	async function act(fn: () => Promise<unknown>, message: string) {
		try {
			await fn();
			toast(message, 'ok');
			reload++;
		} catch (e) {
			toastError(e);
		}
	}

	function actions(m: Member, g: number): MenuItem[] {
		if (m.userId === session.me?.user.id) return [];
		const out: MenuItem[] = [];
		const base = `/api/groups/${g}/members/${m.userId}`;
		if (m.role === 'student' && can('assign_deputy', g))
			out.push({
				label: 'Сделать замом',
				onclick: () => act(() => put(`${base}/role`, { role: 'deputy' }), 'Роль изменена')
			});
		if (m.role === 'deputy' && can('assign_deputy', g))
			out.push({
				label: 'Сделать студентом',
				onclick: () => act(() => put(`${base}/role`, { role: 'student' }), 'Роль изменена')
			});
		if (m.role !== 'headman' && can('assign_headman', g))
			out.push({
				label: 'Назначить старостой',
				onclick: () => act(() => put(`${base}/role`, { role: 'headman' }), 'Роль изменена')
			});
		if (can('reset_passwords', g) && m.role !== 'headman')
			out.push({
				label: 'Ссылка для сброса пароля',
				onclick: async () => {
					try {
						const r = await post<{ path: string }>(`${base}/reset-link`);
						link = absolute(r.path);
					} catch (e) {
						toastError(e);
					}
				}
			});
		if (can('block_users', g) && m.role !== 'headman') {
			out.push(
				m.status === 'blocked'
					? {
							label: 'Разблокировать',
							onclick: () => act(() => post(`${base}/unblock`), 'Разблокирован')
						}
					: {
							label: 'Заблокировать',
							danger: true,
							onclick: () =>
								confirm(`Заблокировать ${m.displayName}? Все сессии будут закрыты.`) &&
								act(() => post(`${base}/block`), 'Заблокирован')
						}
			);
			out.push({
				label: 'Исключить из группы',
				danger: true,
				onclick: () =>
					confirm(`Исключить ${m.displayName} из группы?`) && act(() => del(base), 'Исключён')
			});
		}
		return out;
	}
</script>

<svelte:head><title>{t.nav.members} · groupbase</title></svelte:head>

<div class="page-head">
	<h1>{t.nav.members}</h1>
	{#if inviteGroup && can('create_invites', inviteGroup.id)}
		<Button variant="primary" href="/settings?tab=invites"><UserPlus size={17} /> Пригласить</Button
		>
	{/if}
</div>

<MemberList groupIds={ids} {actions} {reload} />

<Modal open={link !== null} title="Ссылка для сброса пароля" onclose={() => (link = null)}>
	<p class="muted">
		Покажите человеку QR-код — он отсканирует его камерой и задаст новый пароль. Или отправьте
		ссылку лично. Она одноразовая и действует 7 дней; старые сессии пользователя закроются.
	</p>
	{#if link}<div class="qr"><QrCode value={link} label="QR-код для сброса пароля" /></div>{/if}
	<div class="linkbox"><code>{link}</code></div>
	{#snippet footer()}
		{#if canShare()}<Button onclick={() => link && share(link, 'Новый пароль для groupbase')}
				>Поделиться</Button
			>{/if}
		<Button variant="primary" onclick={() => link && copy(link, 'Ссылка скопирована')}
			>Скопировать</Button
		>
	{/snippet}
</Modal>

<style>
	.qr {
		width: min(240px, 70%);
		margin: var(--s3) auto 0;
	}
	.linkbox {
		margin-top: var(--s3);
		padding: 12px;
		background: var(--surface-2);
		border-radius: var(--r-s);
		word-break: break-all;
		font-size: 13px;
	}
</style>
