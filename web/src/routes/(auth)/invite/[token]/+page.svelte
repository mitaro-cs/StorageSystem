<script lang="ts">
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { onMount } from 'svelte';
	import { CircleCheck, KeyRound, QrCode, UserPlus } from '@lucide/svelte';
	import { ApiError, get, post } from '$lib/api';
	import { t } from '$lib/i18n/ru';
	import Button from '$lib/ui/Button.svelte';
	import PasswordFields from '$lib/auth/PasswordFields.svelte';
	import FioField from '$lib/auth/FioField.svelte';
	import QrLogin from '$lib/auth/QrLogin.svelte';
	import { fioError, firstName, suggestUsername } from '$lib/names';
	import type { GroupRole } from '$lib/types';

	interface Info {
		groupId: number;
		groupName: string;
		university: string;
		role: GroupRole;
		valid: boolean;
	}
	interface MeShort {
		user: { displayName: string };
		groups: { id: number }[];
	}

	let info = $state<Info | null>(null);
	let invalid = $state('');
	let me = $state<MeShort | null>(null);
	/** Кто открыл ссылку: новенький регистрируется, у кого аккаунт уже есть — входит. */
	let mode = $state<'new' | 'existing'>('new');
	let qr = $state(false);
	let displayName = $state('');
	let username = $state('');
	let usernameTouched = $state(false);
	$effect(() => {
		if (!usernameTouched) username = suggestUsername(displayName);
	});
	let password = $state('');
	let confirm = $state('');
	let error = $state('');
	let taken = $state(false);
	let busy = $state(false);
	const token = $derived(page.params.token);
	const member = $derived(!!me && !!info && me.groups.some((g) => g.id === info?.groupId));

	async function loadMe() {
		try {
			me = await get<MeShort>('/api/me', { anonymous: true });
		} catch {
			me = null;
		}
	}

	onMount(async () => {
		try {
			info = await get<Info>(`/api/invites/${token}`, { anonymous: true });
			if (!info.valid) invalid = 'Приглашение истекло, отозвано или исчерпано';
		} catch (e) {
			invalid = e instanceof Error ? e.message : 'Приглашение недействительно';
		}
		await loadMe();
	});

	async function register(e: SubmitEvent) {
		e.preventDefault();
		error = fioError(displayName) || (password !== confirm ? 'Пароли не совпадают' : '');
		taken = false;
		if (error) return;
		busy = true;
		try {
			await post(
				`/api/invites/${token}/accept`,
				{ username, displayName, password },
				{ anonymous: true }
			);
			await goto('/', { replaceState: true, invalidateAll: true });
		} catch (err) {
			error = err instanceof Error ? err.message : 'Ошибка';
			// Логин занят — скорее всего, человек уже регистрировался (например, с телефона).
			taken = err instanceof ApiError && err.code === 'username_taken';
		} finally {
			busy = false;
		}
	}

	async function join() {
		busy = true;
		error = '';
		try {
			await post(`/api/invites/${token}/join`);
			await goto('/', { replaceState: true, invalidateAll: true });
		} catch (err) {
			error = err instanceof Error ? err.message : 'Ошибка';
		} finally {
			busy = false;
		}
	}

	async function signedInByQr() {
		qr = false;
		await loadMe();
	}
</script>

<svelte:head><title>Приглашение · groupbase</title></svelte:head>

{#if invalid}
	<h1>Приглашение не работает</h1>
	<p class="muted">{invalid}. Попросите у старосты новую ссылку.</p>
	<p class="faint small">
		Уже есть аккаунт? <a href="/login">Войдите</a> — ссылка для этого не нужна.
	</p>
{:else if info}
	<p class="eyebrow">Приглашение</p>
	<h1>{info.groupName}</h1>
	<p class="muted">
		{info.university}{info.university ? ' · ' : ''}роль: {t.roles[info.role].toLowerCase()}
	</p>

	{#if me && member}
		<div class="done">
			<CircleCheck size={40} />
			<p>
				<strong>{firstName(me.user.displayName)}, вы уже в этой группе.</strong><br />
				<span class="muted"
					>Вход на этом устройстве выполнен — можно пользоваться сайтом, вступать ещё раз не нужно.</span
				>
			</p>
			<Button variant="primary" onclick={() => goto('/', { replaceState: true })}
				>Открыть группу</Button
			>
		</div>
	{:else if me}
		<form onsubmit={(e) => (e.preventDefault(), join())}>
			<p>
				Вы вошли как <strong>{me.user.displayName}</strong>. Вступить в эту группу с этим аккаунтом?
			</p>
			{#if error}<p class="error-text" role="alert">{error}</p>{/if}
			<Button variant="primary" type="submit" loading={busy}>Вступить</Button>
		</form>
	{:else}
		<div class="seg" role="tablist" aria-label="Есть ли у вас аккаунт">
			<button
				role="tab"
				aria-selected={mode === 'new'}
				class:on={mode === 'new'}
				onclick={() => (mode = 'new')}><UserPlus size={17} /> Я здесь впервые</button
			>
			<button
				role="tab"
				aria-selected={mode === 'existing'}
				class:on={mode === 'existing'}
				onclick={() => (mode = 'existing')}><KeyRound size={17} /> У меня есть аккаунт</button
			>
		</div>

		{#if mode === 'existing'}
			<div class="existing">
				<p class="muted">
					Уже регистрировались — например, с телефона? Второй аккаунт не нужен: просто войдите на
					этом устройстве.
				</p>
				<Button variant="primary" href="/login?next=/invite/{token}"
					><KeyRound size={17} /> Войти по логину и паролю</Button
				>
				{#if qr}
					<QrLogin onsuccess={signedInByQr} />
				{:else}
					<Button onclick={() => (qr = true)}
						><QrCode size={17} /> Войти по QR-коду с телефона</Button
					>
					<p class="faint small">
						QR-код появится здесь — отсканируйте его телефоном, где вы уже вошли в groupbase.
					</p>
				{/if}
			</div>
		{:else}
			<form onsubmit={register}>
				<FioField bind:value={displayName} />
				<div>
					<label class="label" for="uname">Имя пользователя для входа</label>
					<input
						id="uname"
						class="input"
						bind:value={username}
						autocomplete="username"
						autocapitalize="none"
						spellcheck="false"
						placeholder="ivanov.ivan"
						oninput={() => (usernameTouched = true)}
						required
					/>
					<p class="hint">Придумали по ФИО — можно поменять. Латиница, цифры, точка или дефис.</p>
				</div>
				<PasswordFields bind:password bind:confirm />
				{#if error}
					<p class="error-text" role="alert">
						{error}{#if taken}. Если это ваш аккаунт —
							<button type="button" class="linkish" onclick={() => (mode = 'existing')}
								>войдите</button
							>, регистрироваться второй раз не нужно.{/if}
					</p>
				{/if}
				<Button variant="primary" type="submit" loading={busy}>Присоединиться</Button>
			</form>
		{/if}
	{/if}
{:else}
	<p class="faint">Проверяем приглашение…</p>
{/if}

<style>
	.seg {
		display: grid;
		grid-template-columns: 1fr 1fr;
		gap: 4px;
		margin-top: var(--s4);
		padding: 4px;
		border-radius: 14px;
		background: var(--surface-2);
	}
	.seg button {
		display: inline-flex;
		align-items: center;
		justify-content: center;
		gap: 6px;
		min-height: 42px;
		padding: 6px 8px;
		border: 0;
		border-radius: 10px;
		background: transparent;
		color: var(--text-2);
		font: inherit;
		font-size: 14px;
		font-weight: 600;
		line-height: 1.2;
		transition:
			background-color var(--dur) var(--ease),
			color var(--dur) var(--ease);
	}
	.seg button.on {
		background: var(--surface);
		color: var(--text);
		box-shadow: 0 1px 3px rgb(16 18 24 / 0.1);
	}
	.existing {
		display: flex;
		flex-direction: column;
		gap: var(--s3);
		margin-top: var(--s4);
		animation: rise 200ms var(--ease);
	}
	.done {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: var(--s3);
		margin-top: var(--s4);
		text-align: center;
		color: var(--ok);
		animation: rise 220ms var(--ease);
	}
	.done p {
		color: var(--text);
		margin: 0;
	}
	.linkish {
		padding: 0;
		border: 0;
		background: none;
		color: inherit;
		font: inherit;
		text-decoration: underline;
		cursor: pointer;
	}
	@keyframes rise {
		from {
			opacity: 0;
			transform: translateY(6px);
		}
	}
</style>
