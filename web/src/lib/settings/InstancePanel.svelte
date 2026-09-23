<script lang="ts">
	import { get, patch, put } from '$lib/api';
	import { t } from '$lib/i18n/ru';
	import { loadMe, session } from '$lib/session.svelte';
	import { toast, toastError } from '$lib/toasts.svelte';
	import type { InstanceRole } from '$lib/types';
	import Avatar from '$lib/ui/Avatar.svelte';
	import Permissions from './Permissions.svelte';

	interface Settings {
		name: string;
		mode: 'single' | 'multi';
		directAccounts: boolean;
		invites: boolean;
	}
	interface UserRow {
		id: number;
		username: string;
		displayName: string;
		avatar: string | null;
		instanceRole: InstanceRole | null;
		status: string;
		totpEnabled: boolean;
	}

	let s = $state<Settings | null>(null);
	let users = $state<UserRow[]>([]);
	let query = $state('');

	$effect(() => {
		get<Settings>('/api/admin/settings').then((r) => (s = r));
		get<UserRow[]>('/api/admin/users').then((r) => (users = r));
	});

	async function update(changes: Partial<Settings>) {
		try {
			s = await patch<Settings>('/api/admin/settings', changes);
			await loadMe();
			toast('Сохранено', 'ok');
		} catch (e) {
			toastError(e);
			s = await get<Settings>('/api/admin/settings');
		}
	}

	async function setRole(u: UserRow, role: string) {
		try {
			await put(`/api/admin/users/${u.id}/instance-role`, { role: role || null });
			u.instanceRole = (role || null) as InstanceRole | null;
			toast('Роль изменена. Пользователю нужно войти заново', 'ok');
		} catch (e) {
			toastError(e);
		}
	}

	const shown = $derived(
		users
			.filter(
				(u) =>
					!query ||
					u.displayName.toLowerCase().includes(query.toLowerCase()) ||
					u.username.includes(query.toLowerCase())
			)
			.slice(0, 50)
	);
</script>

{#if s}
	<section class="card form">
		<h2>Инстанс</h2>
		<div>
			<label class="label" for="i-name">Название</label>
			<input
				id="i-name"
				class="input"
				value={s.name}
				onchange={(e) => update({ name: e.currentTarget.value })}
				maxlength="60"
			/>
		</div>
		<fieldset>
			<legend class="label">Режим</legend>
			<label class="check"
				><input
					type="radio"
					name="mode"
					checked={s.mode === 'single'}
					onchange={() => update({ mode: 'single' })}
				/> Одна группа</label
			>
			<label class="check"
				><input
					type="radio"
					name="mode"
					checked={s.mode === 'multi'}
					onchange={() => update({ mode: 'multi' })}
				/> Несколько групп (поток)</label
			>
		</fieldset>
		<fieldset>
			<legend class="label">Способы создания аккаунтов</legend>
			<label class="check"
				><input
					type="checkbox"
					checked={s.directAccounts}
					onchange={(e) => update({ directAccounts: e.currentTarget.checked })}
				/> Прямое создание старостой</label
			>
			<label class="check"
				><input
					type="checkbox"
					checked={s.invites}
					onchange={(e) => update({ invites: e.currentTarget.checked })}
				/> Инвайт-ссылки</label
			>
		</fieldset>
	</section>
{/if}

<h2 class="sub">Права по умолчанию</h2>
<Permissions groupId={null} />

<h2 class="sub">Администраторы и модераторы</h2>
<input
	class="input filter"
	type="search"
	placeholder="Найти пользователя"
	bind:value={query}
	aria-label="Поиск пользователя"
/>
<div class="list">
	{#each shown as u (u.id)}
		<div class="list-row">
			<Avatar id={u.id} name={u.displayName} avatar={u.avatar} size={32} />
			<div class="info">
				<strong>{u.displayName}</strong><span class="faint small"
					>@{u.username}{u.instanceRole && !u.totpEnabled ? ' · 2FA не включена' : ''}</span
				>
			</div>
			<select
				class="select role"
				value={u.instanceRole ?? ''}
				onchange={(e) => setRole(u, e.currentTarget.value)}
				disabled={u.id === session.me?.user.id}
				aria-label="Роль инстанса"
			>
				<option value="">—</option>
				<option value="moderator">{t.roles.moderator}</option>
				<option value="admin">{t.roles.admin}</option>
			</select>
		</div>
	{/each}
</div>

<style>
	.form {
		display: flex;
		flex-direction: column;
		gap: var(--s4);
		padding: var(--s5);
	}
	fieldset {
		border: 0;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 8px;
	}
	.sub {
		font-size: 17px;
		margin: var(--s5) 0 var(--s3);
	}
	.filter {
		margin-bottom: var(--s3);
	}
	.info {
		flex: 1;
		min-width: 0;
		display: flex;
		flex-direction: column;
	}
	.role {
		width: auto;
		min-width: 150px;
	}
</style>
