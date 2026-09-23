<script lang="ts">
	import RecoveryCodes from '$lib/auth/RecoveryCodes.svelte';
	import QrCode from '$lib/ui/QrCode.svelte';
	import NotificationSettings from '$lib/settings/NotificationSettings.svelte';
	import { fioError } from '$lib/names';
	import { goto } from '$app/navigation';
	import { Download, LogOut, Settings, Users, Newspaper, BookOpen } from '@lucide/svelte';
	import { ApiError, del, get, patch, post, request } from '$lib/api';
	import { offline, wipeOffline } from '$lib/offline/engine';
	import OfflineSettings from '$lib/settings/OfflineSettings.svelte';
	import { t } from '$lib/i18n/ru';
	import { loadMe, session } from '$lib/session.svelte';
	import { toast, toastError } from '$lib/toasts.svelte';
	import Avatar from '$lib/ui/Avatar.svelte';
	import Button from '$lib/ui/Button.svelte';
	import Modal from '$lib/ui/Modal.svelte';
	import PasswordFields from '$lib/auth/PasswordFields.svelte';
	import ThemeToggle from '$lib/shell/ThemeToggle.svelte';
	import AvatarCropper from '$lib/ui/AvatarCropper.svelte';
	import { forgetOfflineData, install, pwa } from '$lib/pwa.svelte';
	import { clearCache } from '$lib/cache';
	import { clearRecent } from '$lib/recent';

	const me = $derived(session.me!);
	let displayName = $derived(me.user.displayName);

	let current = $state('');
	let password = $state('');
	let confirm = $state('');
	let totpOpen = $state(false);
	let totpUri = $state('');
	let totpSecret = $state('');
	let code = $state('');
	// Резервные коды: показываются после включения 2FA или по запросу нового набора.
	let recoveryCodes = $state<string[] | null>(null);
	let recoveryLeft = $state<number | null>(null);
	let regenOpen = $state(false);
	let regenCode = $state('');
	let deleteOpen = $state(false);
	let deletePassword = $state('');
	let avatarOpen = $state(false);

	async function removeAvatar() {
		try {
			await del('/api/me/avatar');
			await loadMe();
		} catch (err) {
			toastError(err);
		}
	}

	async function saveName(e: SubmitEvent) {
		e.preventDefault();
		const problem = fioError(displayName);
		if (problem) return toastError(new Error(problem));
		try {
			await patch('/api/me', { displayName });
			await loadMe();
			toast('ФИО сохранено', 'ok');
		} catch (err) {
			toastError(err);
		}
	}

	async function changePassword(e: SubmitEvent) {
		e.preventDefault();
		if (password !== confirm) return toastError(new Error('Пароли не совпадают'));
		try {
			await post('/api/me/password', { current, password });
			current = password = confirm = '';
			toast('Пароль изменён. Другие устройства вышли из аккаунта', 'ok');
		} catch (err) {
			toastError(err);
		}
	}

	async function startTotp() {
		try {
			const r = await post<{ secret: string; uri: string }>('/api/me/totp/setup');
			totpUri = r.uri;
			totpSecret = r.secret;
			code = '';
			totpOpen = true;
		} catch (err) {
			toastError(err);
		}
	}

	async function finishTotp() {
		try {
			const r = await post<{ recoveryCodes: string[] }>('/api/me/totp/enable', { code });
			recoveryCodes = r.recoveryCodes;
			await loadMe();
			toast('Двухфакторная защита включена', 'ok');
		} catch (err) {
			toastError(err);
		}
	}

	$effect(() => {
		if (me.user.totpEnabled && recoveryLeft === null)
			get<{ remaining: number }>('/api/me/totp/recovery')
				.then((r) => (recoveryLeft = r.remaining))
				.catch(() => {});
	});

	async function regenerate() {
		try {
			const r = await post<{ recoveryCodes: string[] }>('/api/me/totp/recovery', {
				code: regenCode
			});
			recoveryCodes = r.recoveryCodes;
			recoveryLeft = r.recoveryCodes.length;
			regenCode = '';
		} catch (err) {
			toastError(err);
		}
	}

	async function disableTotp() {
		const c = prompt('Введите код из приложения, чтобы отключить 2FA');
		if (!c) return;
		try {
			await post('/api/me/totp/disable', { code: c });
			await loadMe();
			toast('2FA отключена', 'ok');
		} catch (err) {
			toastError(err);
		}
	}

	/** Всё, что хранилось на устройстве, стирается: копия данных, файлы, недавнее. */
	async function forgetDevice() {
		forgetOfflineData();
		await wipeOffline();
		clearCache();
		clearRecent();
		session.me = null;
	}

	async function logout() {
		if (
			offline.pending > 0 &&
			!window.confirm(
				`Без сети сделано действий: ${offline.pending}. Они ещё не отправлены и пропадут. Выйти?`
			)
		)
			return;
		try {
			await request('/api/auth/logout', { method: 'POST', body: {} });
		} catch (err) {
			return toastError(
				err instanceof ApiError && err.status === 0
					? new Error('Выйти можно, когда появится интернет')
					: err
			);
		}
		await forgetDevice();
		goto('/login', { replaceState: true });
	}

	async function deleteAccount() {
		try {
			await del('/api/me', { password: deletePassword });
			await forgetDevice();
			goto('/login', { replaceState: true });
		} catch (err) {
			toastError(err);
		}
	}
</script>

<svelte:head><title>Профиль · groupbase</title></svelte:head>

<header class="me-head">
	<button
		class="avatar-btn"
		onclick={() => (avatarOpen = true)}
		aria-label="Сменить аватар"
		title="Сменить аватар"
	>
		<Avatar id={me.user.id} name={me.user.displayName} avatar={me.user.avatar} size={72} />
		<span class="edit" aria-hidden="true">Изменить</span>
	</button>
	<div>
		<h1>{me.user.displayName}</h1>
		<p class="muted">
			@{me.user.username}{me.user.instanceRole ? ` · ${t.roles[me.user.instanceRole]}` : ''}
		</p>
		<div class="roles">
			{#each me.groups.filter((g) => g.role) as g (g.id)}<span class="chip"
					>{g.name} · {t.roles[g.role!].toLowerCase()}</span
				>{/each}
		</div>
		{#if me.user.avatar}
			<button class="linklike small" onclick={removeAvatar}>Убрать аватар</button>
		{/if}
	</div>
</header>

<nav class="quick mobile" aria-label="Разделы">
	<a href="/news"><Newspaper size={18} /> {t.nav.news}</a>
	<a href="/subjects"><BookOpen size={18} /> {t.nav.subjects}</a>
	<a href="/members"><Users size={18} /> {t.nav.members}</a>
	<a href="/settings"><Settings size={18} /> {t.nav.settings}</a>
</nav>

<section class="card block">
	<h2>ФИО</h2>
	<form class="row" onsubmit={saveName}>
		<input
			class="input"
			bind:value={displayName}
			maxlength="64"
			autocomplete="name"
			placeholder="Иванов Иван Иванович"
			aria-label="ФИО"
		/>
		<Button type="submit">Сохранить</Button>
	</form>
	<div class="row theme">
		<span>Тема оформления</span><span class="spacer"></span><ThemeToggle />
	</div>
</section>

<NotificationSettings />

<OfflineSettings />

<section class="card block">
	<h2>Пароль</h2>
	<form class="stack" onsubmit={changePassword}>
		<input type="text" autocomplete="username" value={me.user.username} hidden readonly />
		<div>
			<label class="label" for="cur">Текущий пароль</label>
			<input
				id="cur"
				class="input"
				type="password"
				autocomplete="current-password"
				bind:value={current}
				required
			/>
		</div>
		<PasswordFields bind:password bind:confirm />
		<div><Button type="submit">Сменить пароль</Button></div>
	</form>
</section>

<section class="card block">
	<h2>Двухфакторная защита</h2>
	<p class="muted">
		Код из приложения на телефоне при каждом входе. {me.user.totpEnabled
			? 'Включена.'
			: 'Выключена.'}
	</p>
	{#if me.user.totpEnabled && recoveryLeft !== null}
		<dl class="kv">
			<div>
				<dt>Резервные коды</dt>
				<dd class:low={recoveryLeft <= 3}>
					осталось <span class="num">{recoveryLeft}</span> из 10
				</dd>
			</div>
		</dl>
	{/if}
	<div class="row wrap">
		{#if me.user.totpEnabled}
			<Button onclick={() => ((regenCode = ''), (recoveryCodes = null), (regenOpen = true))}
				>Новые резервные коды</Button
			>
			{#if !me.user.instanceRole || !me.instance.requireStaffTotp}<Button onclick={disableTotp}
					>Отключить</Button
				>{/if}
		{:else}
			<Button variant="primary" onclick={startTotp}>Включить</Button>
		{/if}
	</div>
</section>

<section class="card block">
	<h2>Приложение</h2>
	<p class="muted">
		groupbase можно установить на телефон как приложение: он откроется без браузерной строки, а
		лента и ДЗ будут доступны без сети.
	</p>
	<div class="row wrap">
		{#if pwa.canInstall}<Button variant="primary" onclick={install}>Установить</Button>{/if}
		<a class="download" href="/install">Как установить — по шагам</a>
	</div>
</section>

<section class="card block">
	<h2>Мои данные</h2>
	<p class="muted">
		ZIP-файл со всем, что связано с вами: профиль, отметки «сделано», комментарии, ваши публикации и
		загруженные файлы.
	</p>
	<div>
		<a class="download" href="/api/me/export" download><Download size={17} /> Скачать мои данные</a>
	</div>
</section>

<section class="card block">
	<h2>Выход и удаление</h2>
	<div class="row wrap">
		<Button onclick={logout}><LogOut size={16} /> Выйти</Button>
		<Button variant="danger" onclick={() => (deleteOpen = true)}>Удалить аккаунт</Button>
	</div>
	<p class="faint small">groupbase {me.instance.version}</p>
</section>

<AvatarCropper
	bind:open={avatarOpen}
	endpoint="/api/me/avatar"
	title="Ваш аватар"
	ondone={() => loadMe()}
/>

<Modal bind:open={totpOpen} title={recoveryCodes ? 'Резервные коды' : 'Включить 2FA'}>
	{#if recoveryCodes}
		<RecoveryCodes
			codes={recoveryCodes}
			ondone={() => ((totpOpen = false), (recoveryCodes = null), (recoveryLeft = null))}
		/>
	{:else}
		<div class="stack">
			<p class="muted">Отсканируйте код приложением-аутентификатором и введите 6 цифр.</p>
			{#if totpUri}<div class="qr"><QrCode value={totpUri} label="QR-код" /></div>{/if}
			<p class="hint">Ключ вручную: <code>{totpSecret}</code></p>
			<input
				class="input num"
				inputmode="numeric"
				autocomplete="one-time-code"
				bind:value={code}
				aria-label="Код"
			/>
		</div>
	{/if}
	{#snippet footer()}
		{#if !recoveryCodes}
			<Button onclick={() => (totpOpen = false)}>Отмена</Button>
			<Button variant="primary" onclick={finishTotp}>Включить</Button>
		{/if}
	{/snippet}
</Modal>

<Modal bind:open={regenOpen} title="Новые резервные коды">
	{#if recoveryCodes}
		<RecoveryCodes
			codes={recoveryCodes}
			ondone={() => ((regenOpen = false), (recoveryCodes = null))}
		/>
	{:else}
		<div class="stack">
			<p class="muted">
				Старые коды перестанут действовать. Для подтверждения введите код из приложения.
			</p>
			<input
				class="input num"
				inputmode="numeric"
				autocomplete="one-time-code"
				bind:value={regenCode}
				aria-label="Код из приложения"
			/>
		</div>
	{/if}
	{#snippet footer()}
		{#if !recoveryCodes}
			<Button onclick={() => (regenOpen = false)}>Отмена</Button>
			<Button variant="primary" onclick={regenerate}>Получить</Button>
		{/if}
	{/snippet}
</Modal>

<Modal bind:open={deleteOpen} title="Удалить аккаунт">
	<div class="stack">
		<p>
			Имя, логин, пароль и аватар будут стёрты. Ваши новости и задания останутся с подписью
			«удалённый пользователь».
		</p>
		<label class="label" for="del-pass">Пароль для подтверждения</label>
		<input
			id="del-pass"
			class="input"
			type="password"
			autocomplete="current-password"
			bind:value={deletePassword}
		/>
	</div>
	{#snippet footer()}
		<Button onclick={() => (deleteOpen = false)}>Отмена</Button>
		<Button variant="danger" onclick={deleteAccount} disabled={!deletePassword}
			>Удалить навсегда</Button
		>
	{/snippet}
</Modal>

<style>
	.me-head {
		display: flex;
		align-items: center;
		gap: var(--s4);
		margin-bottom: var(--s5);
	}
	.avatar-btn {
		position: relative;
		padding: 0;
		border: 0;
		background: none;
		border-radius: 50%;
	}
	.edit {
		position: absolute;
		inset: 0;
		display: grid;
		place-items: center;
		border-radius: 50%;
		background: rgb(0 0 0 / 0.45);
		color: #fff;
		font-size: 12px;
		font-weight: 600;
		opacity: 0;
		transition: opacity var(--dur) var(--ease);
	}
	.avatar-btn:hover .edit,
	.avatar-btn:focus-visible .edit {
		opacity: 1;
	}
	.linklike {
		border: 0;
		background: none;
		padding: 0;
		color: var(--text-3);
	}
	.linklike:hover {
		color: var(--danger);
	}
	.roles {
		display: flex;
		flex-wrap: wrap;
		gap: 6px;
		margin-top: 8px;
	}
	.quick {
		display: grid;
		grid-template-columns: 1fr 1fr;
		gap: 8px;
		margin-bottom: var(--s4);
	}
	.quick a {
		display: flex;
		align-items: center;
		gap: 10px;
		height: 48px;
		padding: 0 14px;
		border-radius: var(--r);
		background: var(--surface);
		box-shadow: var(--shadow-1);
		color: var(--text);
		font-weight: 550;
	}
	.quick a:hover {
		text-decoration: none;
	}
	@media (min-width: 900px) {
		.mobile {
			display: none;
		}
	}
	.block {
		display: flex;
		flex-direction: column;
		gap: var(--s3);
		padding: var(--s5);
		margin-bottom: var(--s3);
	}
	.block h2 {
		font-size: 17px;
	}
	.theme {
		padding-top: var(--s2);
	}
	.wrap {
		flex-wrap: wrap;
	}
	.qr {
		width: 200px;
		align-self: center;
		border-radius: var(--r);
	}
	.kv dd.low {
		color: var(--danger);
	}
	.download {
		display: inline-flex;
		align-items: center;
		gap: 8px;
		height: 44px;
		padding: 0 20px;
		border: 1px solid var(--border-strong);
		border-radius: var(--r-full);
		background: var(--surface);
		color: var(--text);
		font-weight: 580;
	}
	.download:hover {
		background: var(--surface-2);
		text-decoration: none;
	}
</style>
