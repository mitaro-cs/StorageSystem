<script lang="ts">
	import { Copy, Link2, ListPlus, Maximize2, Share2, UserPlus } from '@lucide/svelte';
	import { post } from '$lib/api';
	import { absolute, canShare, copy, share } from '$lib/copy';
	import { t } from '$lib/i18n/ru';
	import { fioError, firstName } from '$lib/names';
	import { can, session } from '$lib/session.svelte';
	import { toastError } from '$lib/toasts.svelte';
	import type { CreatedAccount, GroupRole } from '$lib/types';
	import Modal from '$lib/ui/Modal.svelte';
	import Button from '$lib/ui/Button.svelte';
	import QrCode from '$lib/ui/QrCode.svelte';
	import QrScreen from '$lib/ui/QrScreen.svelte';
	import FioField from '$lib/auth/FioField.svelte';
	import Accounts from '$lib/settings/Accounts.svelte';

	// Добавить людей в группу — три способа на одном экране, от самого простого.
	interface Props {
		open: boolean;
		groupId: number;
		onadded?: () => void;
	}

	let { open = $bindable(), groupId, onadded }: Props = $props();

	const canCreate = $derived(can('create_accounts', groupId));
	const canInvite = $derived(can('create_invites', groupId));
	const groupName = $derived(session.me?.groups.find((g) => g.id === groupId)?.name ?? '');

	let tab = $state<'one' | 'list' | 'link'>('one');
	let fio = $state('');
	let role = $state<GroupRole>('student');
	let busy = $state(false);
	let error = $state('');
	let created = $state<CreatedAccount | null>(null);
	let invite = $state<string | null>(null);
	let fullscreen = $state<{ value: string; title: string; hint: string } | null>(null);
	// Случайный клик мимо окна не стирает вписанное ФИО и не прячет ссылку для входа.
	const dirty = $derived(!!fio.trim() || !!created);

	$effect(() => {
		if (!open) return;
		tab = canCreate ? 'one' : 'link';
		fio = '';
		role = 'student';
		error = '';
		created = null;
		invite = null;
	});

	async function createOne(e: SubmitEvent) {
		e.preventDefault();
		error = fioError(fio);
		if (error) return;
		busy = true;
		try {
			const r = await post<CreatedAccount[]>(`/api/groups/${groupId}/accounts`, {
				accounts: [{ displayName: fio.trim() }],
				role,
				delivery: 'LINK'
			});
			created = r[0];
			onadded?.();
		} catch (err) {
			error = err instanceof Error ? err.message : 'Ошибка';
		} finally {
			busy = false;
		}
	}

	async function createInvite() {
		busy = true;
		try {
			const r = await post<{ path: string }>(`/api/groups/${groupId}/invites`, {
				role,
				maxUses: null,
				ttlHours: 168,
				note: 'Из раздела «Люди»'
			});
			invite = absolute(r.path);
		} catch (err) {
			toastError(err);
		} finally {
			busy = false;
		}
	}

	const activation = $derived(created?.activationPath ? absolute(created.activationPath) : '');
</script>

<Modal
	bind:open
	{dirty}
	dirtyText={created
		? 'Ссылку для входа потом не показать — сначала отправьте её человеку.'
		: 'Вписанное не сохранится.'}
	title="Добавить людей"
	wide
>
	<div class="tabs" role="tablist" aria-label="Способ">
		{#if canCreate}
			<button
				role="tab"
				aria-selected={tab === 'one'}
				class:on={tab === 'one'}
				onclick={() => (tab = 'one')}><UserPlus size={16} /> Один человек</button
			>
			<button
				role="tab"
				aria-selected={tab === 'list'}
				class:on={tab === 'list'}
				onclick={() => (tab = 'list')}><ListPlus size={16} /> Списком</button
			>
		{/if}
		{#if canInvite}
			<button
				role="tab"
				aria-selected={tab === 'link'}
				class:on={tab === 'link'}
				onclick={() => (tab = 'link')}><Link2 size={16} /> Ссылкой</button
			>
		{/if}
	</div>

	{#if tab === 'one'}
		{#if created}
			<div class="made">
				<p class="lead">
					<strong>{created.displayName}</strong> добавлен(а). Покажите QR-код — {firstName(
						created.displayName
					)} отсканирует его камерой телефона и сам(а) задаст пароль.
				</p>
				<div class="qr"><QrCode value={activation} label="QR-код активации" /></div>
				<p class="faint small center">
					Логин: <code>{created.username}</code> · ссылка одноразовая, действует 7 дней
				</p>
				<div class="row wrap center">
					<Button
						onclick={() =>
							(fullscreen = {
								value: activation,
								title: created?.displayName ?? '',
								hint: 'Отсканируйте камерой и задайте свой пароль'
							})}><Maximize2 size={16} /> На весь экран</Button
					>
					<Button onclick={() => copy(activation, 'Ссылка скопирована')}
						><Copy size={16} /> Копировать</Button
					>
					{#if canShare()}<Button onclick={() => share(activation, 'Вход в groupbase')}
							><Share2 size={16} /> Отправить</Button
						>{/if}
				</div>
				<Button variant="primary" onclick={() => ((created = null), (fio = ''))}
					>Добавить ещё</Button
				>
			</div>
		{:else}
			<form id="add-one" class="stack form" onsubmit={createOne}>
				<p class="muted small">
					Самый быстрый способ на паре: впишите ФИО — появится QR-код, человек отсканирует его и сам
					задаст пароль. Логин придумается по ФИО.
				</p>
				<FioField bind:value={fio} />
				<div>
					<label class="label" for="add-role">Роль в группе</label>
					<select id="add-role" class="select" bind:value={role}>
						<option value="student">{t.roles.student}</option>
						{#if can('assign_deputy', groupId)}<option value="deputy">{t.roles.deputy}</option>{/if}
						{#if can('assign_headman', groupId)}<option value="headman">{t.roles.headman}</option
							>{/if}
					</select>
				</div>
				{#if error}<p class="error-text" role="alert">{error}</p>{/if}
				<Button variant="primary" type="submit" loading={busy}>Добавить и показать QR-код</Button>
			</form>
		{/if}
	{:else if tab === 'list'}
		<p class="muted small list-hint">
			Вставьте список группы из таблицы или чата — по ФИО на строку. Каждому достанется своя ссылка
			с QR-кодом: можно распечатать и раздать.
		</p>
		<Accounts {groupId} {groupName} embedded oncreated={onadded} />
	{:else}
		<div class="stack form">
			<p class="muted small">
				Одна ссылка на всех: каждый сам впишет ФИО и пароль. Удобно отправить в чат группы. Если
				человек уже зарегистрирован, по этой же ссылке он просто войдёт на новом устройстве.
			</p>
			{#if invite}
				<div class="qr"><QrCode value={invite} label="QR-код приглашения" /></div>
				<div class="linkbox"><code>{invite}</code></div>
				<div class="row wrap center">
					<Button
						onclick={() =>
							(fullscreen = {
								value: invite ?? '',
								title: groupName,
								hint: 'Наведите камеру телефона на код, чтобы присоединиться'
							})}><Maximize2 size={16} /> На весь экран</Button
					>
					<Button onclick={() => invite && copy(invite, 'Ссылка скопирована')}
						><Copy size={16} /> Копировать</Button
					>
					{#if canShare()}<Button onclick={() => invite && share(invite, `Группа ${groupName}`)}
							><Share2 size={16} /> Отправить</Button
						>{/if}
				</div>
				<p class="faint small center">
					Действует неделю. Все ссылки — в «Настройки → Приглашения».
				</p>
			{:else}
				<div>
					<label class="label" for="inv-role2">Кого приглашаем</label>
					<select id="inv-role2" class="select" bind:value={role}>
						<option value="student">{t.roles.student}</option>
						{#if can('assign_deputy', groupId)}<option value="deputy">{t.roles.deputy}</option>{/if}
					</select>
				</div>
				<Button variant="primary" onclick={createInvite} loading={busy}
					><Link2 size={16} /> Создать ссылку и QR-код</Button
				>
			{/if}
		</div>
	{/if}
</Modal>

{#if fullscreen}
	<QrScreen
		open={true}
		value={fullscreen.value}
		title={fullscreen.title}
		hint={fullscreen.hint}
		onclose={() => (fullscreen = null)}
	/>
{/if}

<style>
	.tabs {
		display: flex;
		gap: 4px;
		padding: 4px;
		margin-bottom: var(--s4);
		border-radius: 14px;
		background: var(--surface-2);
		overflow-x: auto;
	}
	.tabs button {
		flex: 1;
		display: inline-flex;
		align-items: center;
		justify-content: center;
		gap: 6px;
		min-height: 40px;
		padding: 0 10px;
		border: 0;
		border-radius: 10px;
		background: transparent;
		color: var(--text-2);
		font: inherit;
		font-size: 14px;
		font-weight: 600;
		white-space: nowrap;
	}
	.tabs button.on {
		background: var(--surface);
		color: var(--text);
		box-shadow: 0 1px 3px rgb(16 18 24 / 0.1);
	}
	.form {
		gap: var(--s4);
	}
	.made {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: var(--s3);
		animation: rise 220ms var(--ease);
	}
	.lead {
		margin: 0;
		text-align: center;
	}
	.qr {
		width: min(240px, 70%);
		margin: 0 auto;
	}
	.center {
		text-align: center;
		justify-content: center;
	}
	.list-hint {
		margin: 0 0 var(--s3);
	}
	.linkbox {
		padding: 12px;
		border-radius: var(--r-s);
		background: var(--surface-2);
		word-break: break-all;
		font-size: 13px;
	}
	@keyframes rise {
		from {
			opacity: 0;
			transform: translateY(6px);
		}
	}
</style>
