<script lang="ts">
	import { get, patch, put } from '$lib/api';
	import { t } from '$lib/i18n/ru';
	import { loadMe, session } from '$lib/session.svelte';
	import { toast, toastError } from '$lib/toasts.svelte';
	import type { InstanceRole } from '$lib/types';
	import Avatar from '$lib/ui/Avatar.svelte';
	import Permissions from './Permissions.svelte';
	import SectionHead from '$lib/ui/SectionHead.svelte';
	import { Crown, Info, KeyRound, ShieldCheck, SlidersHorizontal } from '@lucide/svelte';
	import { ask } from '$lib/ui/ask.svelte';

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
		const self = u.id === session.me?.user.id;
		if (
			self &&
			role !== 'admin' &&
			!(await ask('Снять с себя роль администратора?', { ok: 'Снять', danger: true }))
		) {
			users = [...users];
			return;
		}
		try {
			await put(`/api/admin/users/${u.id}/instance-role`, { role: role || null });
			u.instanceRole = (role || null) as InstanceRole | null;
			if (self) await loadMe();
			toast(self ? 'Ваша роль изменена' : 'Роль изменена. Человеку нужно войти заново', 'ok');
		} catch (e) {
			toastError(e);
			users = await get<UserRow[]>('/api/admin/users');
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

<section class="card explain">
	<SectionHead icon={Info} tone="blue" title="Что такое «сайт»" />
	<p>
		<strong>Сайт</strong> (в технических текстах — <em>инстанс</em>) — это весь ваш groupbase
		целиком: одна группа или несколько групп потока, их общие настройки и администраторы. У каждой
		группы свой староста, предметы и задания, а у сайта — <strong>администратор</strong>, который
		отвечает за всё сразу: группы, роли, доступ из интернета и резервные копии.
	</p>
	<ul class="facts">
		<li><strong>Администратор</strong> — всё: группы, роли, сервер. Обычно это хост.</li>
		<li>
			<strong>Модератор</strong> — следит за порядком во всех группах: разбирает жалобы в «Модерации»,
			скрывает и удаляет лишнее. Людей не блокирует и настройки не трогает.
		</li>
		<li><strong>Староста, зам, студент</strong> — роли внутри одной группы.</li>
	</ul>
</section>

{#if s}
	<section class="card form">
		<SectionHead icon={SlidersHorizontal} tone="violet" title="Основное" />
		<div>
			<label class="label" for="i-name">Название</label>
			<input
				id="i-name"
				class="input"
				value={s.name}
				onchange={(e) => update({ name: e.currentTarget.value })}
				maxlength="60"
			/>
			<p class="hint">Видно в заголовке и при входе. Например, «Поток БИН-25» или «БИН2509».</p>
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
	</section>
	<section class="card form">
		<SectionHead
			icon={KeyRound}
			tone="teal"
			title="Как люди попадают на сайт"
			text="Можно оставить оба способа: старосте удобнее добавить всех списком, а новенький сам зайдёт по ссылке."
		/>
		<fieldset>
			<label class="check"
				><input
					type="checkbox"
					checked={s.directAccounts}
					onchange={(e) => update({ directAccounts: e.currentTarget.checked })}
				/> Староста создаёт аккаунты сам — списком или по одному, с QR-кодом</label
			>
			<label class="check"
				><input
					type="checkbox"
					checked={s.invites}
					onchange={(e) => update({ invites: e.currentTarget.checked })}
				/> Ссылки-приглашения: человек сам вписывает ФИО и пароль</label
			>
		</fieldset>
	</section>
{/if}

<section class="card form">
	<SectionHead
		icon={ShieldCheck}
		tone="green"
		title="Права по умолчанию для всех групп"
		text="Староста может поменять их в своей группе."
	/>
	<Permissions groupId={null} />
</section>

<section class="card form">
	<SectionHead
		icon={Crown}
		tone="amber"
		title="Администраторы и модераторы"
		text="Выберите роль у человека. Себе тоже можно — но последнего администратора снять нельзя."
	/>
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
					aria-label="Роль на сайте: {u.displayName}"
				>
					<option value="">Без роли на сайте</option>
					<option value="moderator">{t.roles.moderator}</option>
					<option value="admin">{t.roles.admin}</option>
				</select>
			</div>
		{/each}
	</div>
</section>

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
	.form + .form,
	.explain + .form {
		margin-top: var(--s4);
	}
	.explain {
		display: flex;
		flex-direction: column;
		gap: var(--s3);
		padding: var(--s5);
		margin-bottom: var(--s4);
		border: 1px dashed var(--border-strong);
		box-shadow: none;
	}
	.explain p {
		margin: 0;
		line-height: 1.55;
	}
	.facts {
		margin: 0;
		padding-left: 20px;
		display: flex;
		flex-direction: column;
		gap: 6px;
		color: var(--text-2);
		font-size: 14.5px;
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
