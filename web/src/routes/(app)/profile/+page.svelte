<script lang="ts">
	import { fioError } from '$lib/names';
	import { goto } from '$app/navigation';
	import {
		BookOpen,
		Database,
		Download,
		Fingerprint,
		KeyRound,
		LogOut,
		MonitorSmartphone,
		Newspaper,
		Palette,
		ScanLine,
		Settings,
		ShieldCheck,
		Smartphone,
		SlidersHorizontal,
		UserRound,
		Users
	} from '@lucide/svelte';
	import SectionHead from '$lib/ui/SectionHead.svelte';
	import { ApiError, del, get, patch, post, request } from '$lib/api';
	import { offline, wipeOffline } from '$lib/offline/engine';
	import { forgetAccount } from '$lib/accounts';
	import { t } from '$lib/i18n/ru';
	import {
		canManage,
		groups,
		hasSettings,
		loadMe,
		manageMode,
		session,
		setManageMode
	} from '$lib/session.svelte';
	import Switch from '$lib/ui/Switch.svelte';
	import { toast, toastError } from '$lib/toasts.svelte';
	import Avatar from '$lib/ui/Avatar.svelte';
	import Button from '$lib/ui/Button.svelte';
	import Modal from '$lib/ui/Modal.svelte';
	import PasswordFields from '$lib/auth/PasswordFields.svelte';
	import { forgetOfflineData, install, pwa } from '$lib/pwa.svelte';
	import { clearCache } from '$lib/cache';
	import { clearRecent } from '$lib/recent';
	import { ask, askText } from '$lib/ui/ask.svelte';
	import { copy } from '$lib/copy';

	const me = $derived(session.me!);
	// Кабинет администратора и старосты: здесь же видны все люди группы с их ролями.
	const seesPeople = $derived(
		manageMode() &&
			(!!me.user.instanceRole || groups().some((g) => g.role === 'headman' || g.role === 'deputy'))
	);
	let displayName = $derived(me.user.displayName);

	let current = $state('');
	let password = $state('');
	let confirm = $state('');
	let totpOpen = $state(false);
	let totpUri = $state('');
	let totpSecret = $state('');
	// На телефоне QR-код с того же экрана не отсканировать — даём ссылку в приложение.
	const onPhone = typeof matchMedia !== 'undefined' && matchMedia('(pointer: coarse)').matches;
	let code = $state('');
	// Резервные коды: показываются после включения 2FA или по запросу нового набора.
	let recoveryCodes = $state<string[] | null>(null);
	let recoveryLeft = $state<number | null>(null);
	let regenOpen = $state(false);
	let regenCode = $state('');
	let deleteOpen = $state(false);
	let scanOpen = $state(false);
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

	async function setManage(on: boolean) {
		try {
			await setManageMode(on);
			toast(on ? 'Кнопки управления снова видны' : 'Кнопки управления скрыты', 'ok');
		} catch (e) {
			toastError(e);
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
		const c = await askText('Введите 6 цифр из приложения-аутентификатора', {
			title: 'Отключить 2FA',
			ok: 'Отключить',
			danger: true,
			inputmode: 'numeric',
			autocomplete: 'one-time-code',
			maxlength: 7
		});
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
	}

	/** Сначала уходим со страницы профиля, потом забываем пользователя — иначе она упадёт. */
	async function leave() {
		await goto('/login', { replaceState: true });
		session.me = null;
	}

	async function logout() {
		if (
			offline.pending > 0 &&
			!(await ask(
				`Без сети сделано действий: ${offline.pending}. Они ещё не отправлены и пропадут. Выйти?`,
				{ title: 'Выйти из аккаунта', ok: 'Выйти', danger: true }
			))
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
		await leave();
	}

	async function deleteAccount() {
		try {
			await del('/api/me', { password: deletePassword });
			forgetAccount(me.user.id);
			await forgetDevice();
			await leave();
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
		<Avatar id={me.user.id} name={me.user.displayName} avatar={me.user.avatar} size={72} ring />
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
	{#if hasSettings()}<a href="/settings"><Settings size={18} /> {t.nav.settings}</a>{/if}
</nav>

{#if canManage()}
	<p class="chapter">Управление</p>
	<section class="card block">
		<SectionHead
			icon={SlidersHorizontal}
			tone="violet"
			title="Режим управления"
			text="Кнопки администратора и старосты: приглашения, права, сервер, модерация. Выключите, чтобы пользоваться сайтом как участник — публиковать новости и задания можно и так."
		>
			<Switch checked={me.user.manageMode} label="Режим управления" onchange={setManage} />
		</SectionHead>
	</section>
	{#if seesPeople}
		<section class="card block" id="people">
			<SectionHead
				icon={Users}
				tone="blue"
				title="Люди"
				text="Все участники группы и их роли. Меню «…» у человека — сменить роль, сбросить пароль, заблокировать."
			/>
			{#await import('$lib/content/People.svelte')}<p class="faint small">
					Загружаем…
				</p>{:then m}<m.default />{/await}
		</section>
	{/if}
{/if}

<p class="chapter">Аккаунт и безопасность</p>
<section class="card block">
	<SectionHead icon={UserRound} tone="gray" title="ФИО" />
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
</section>

<section class="card block">
	<SectionHead
		icon={Palette}
		tone="violet"
		title="Оформление"
		text="Светлая или тёмная тема, цвет и стиль карточек — под себя. Меняется сразу."
	/>
	<!-- Код выбора оформления грузится отдельно: он ниже первого экрана профиля. -->
	{#await import('$lib/shell/ThemePicker.svelte') then m}<m.default />{/await}
</section>

<section class="card block">
	<SectionHead
		icon={MonitorSmartphone}
		tone="teal"
		title="Вход на другом устройстве"
		text="На ноутбуке или втором телефоне откройте groupbase, выберите «По QR-коду» и отсканируйте код отсюда — логин и пароль вводить не придётся."
	/>
	<div>
		<Button onclick={() => (scanOpen = true)}><ScanLine size={17} /> Сканировать QR-код</Button>
	</div>
</section>
{#if scanOpen}
	{#await import('$lib/auth/QrScanner.svelte') then m}<m.default bind:open={scanOpen} />{/await}
{/if}

<section class="card block">
	<SectionHead icon={KeyRound} tone="amber" title="Пароль" />
	<form class="stack" onsubmit={changePassword}>
		<input type="text" autocomplete="username" value={me.user.username} hidden readonly />
		{#if me.hostWindow}
			<!-- Окно приложения на компьютере хоста: забытый пароль можно просто задать заново. -->
			<p class="small muted">
				Вы на компьютере, где работает сервер группы, — старый пароль не нужен. Новый пароль
				понадобится для входа с телефона.
			</p>
		{:else}
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
		{/if}
		<PasswordFields bind:password bind:confirm />
		<div><Button type="submit">Сменить пароль</Button></div>
	</form>
</section>

<section class="card block">
	<SectionHead
		icon={ShieldCheck}
		tone="green"
		title="Двухфакторная защита"
		text="Код из приложения на телефоне при каждом входе. {me.user.totpEnabled
			? 'Включена.'
			: 'Выключена.'}"
	/>
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

{#if !me.hostWindow}
	<section class="card block">
		<SectionHead
			icon={Fingerprint}
			tone="violet"
			title="Вход по отпечатку или лицу"
			text="Ключ на телефоне или ноутбуке вместо пароля и кода: приложили палец — и вы вошли. Подделать или выманить его нельзя."
		/>
		{#await import('$lib/auth/PasskeysPanel.svelte') then m}<m.default />{/await}
	</section>
{/if}

<p class="chapter">Уведомления и офлайн</p>
<!-- Уведомления и офлайн — ниже первого экрана: их код грузится отдельно. -->
{#await import('$lib/settings/NotificationSettings.svelte') then m}<m.default />{/await}

{#await import('$lib/settings/OfflineSettings.svelte') then m}<m.default />{/await}

<p class="chapter">Прочее</p>
<section class="card block">
	<SectionHead
		icon={Smartphone}
		tone="blue"
		title="Приложение на телефон"
		text="groupbase можно установить как приложение: откроется без браузерной строки, а лента и ДЗ будут доступны без сети."
	/>
	<div class="row wrap">
		{#if pwa.canInstall}<Button variant="primary" onclick={install}>Установить</Button>{/if}
		<a class="download" href="/install">Как установить — по шагам</a>
	</div>
</section>

<section class="card block">
	<SectionHead
		icon={Database}
		tone="gray"
		title="Мои данные"
		text="ZIP-файл со всем, что связано с вами: профиль, отметки «сделано», комментарии, ваши публикации и загруженные файлы."
	/>
	<div>
		<a class="download" href="/api/me/export" download><Download size={17} /> Скачать мои данные</a>
	</div>
</section>

<section class="card block">
	<SectionHead icon={LogOut} tone="red" title="Выход и удаление" />
	<div class="row wrap">
		<Button onclick={logout}><LogOut size={16} /> Выйти</Button>
		<Button variant="danger" onclick={() => (deleteOpen = true)}>Удалить аккаунт</Button>
	</div>
	<p class="faint small">groupbase {me.instance.version}</p>
</section>

<!-- Редкие окна (аватар, 2FA, резервные коды) грузят свой код только при открытии. -->
{#if avatarOpen}
	{#await import('$lib/ui/AvatarCropper.svelte') then m}
		<m.default
			bind:open={avatarOpen}
			endpoint="/api/me/avatar"
			title="Ваш аватар"
			ondone={() => loadMe()}
		/>
	{/await}
{/if}

<Modal bind:open={totpOpen} title={recoveryCodes ? 'Резервные коды' : 'Включить 2FA'}>
	{#if recoveryCodes}
		{@const codes = recoveryCodes}
		{#await import('$lib/auth/RecoveryCodes.svelte') then m}
			<m.default
				{codes}
				ondone={() => ((totpOpen = false), (recoveryCodes = null), (recoveryLeft = null))}
			/>
		{/await}
	{:else}
		<form
			id="totp-form"
			class="stack"
			onsubmit={(e) => {
				e.preventDefault();
				finishTotp();
			}}
		>
			<p class="muted">
				{onPhone
					? 'Добавьте ключ в приложение-аутентификатор на этом телефоне или отсканируйте QR-код с другого устройства.'
					: 'Отсканируйте QR-код приложением-аутентификатором на телефоне.'}
				Подойдёт любое: «Пароли» на iPhone, Google Authenticator, Яндекс Ключ, Microsoft Authenticator.
			</p>
			{#if onPhone && totpUri}
				<!-- otpauth:// открывает приложение-аутентификатор этого телефона: QR с того же экрана не
				     отсканировать. -->
				<Button variant="primary" href={totpUri}>Добавить в приложение на этом телефоне</Button>
			{/if}
			{#if totpUri}
				{#await import('$lib/ui/QrCode.svelte') then m}
					<div class="qr"><m.default value={totpUri} label="QR-код" /></div>
				{/await}
			{/if}
			<div class="secret">
				<span class="hint">Или введите ключ вручную:</span>
				<code class="num">{totpSecret.replace(/(.{4})/g, '$1 ').trim()}</code>
				<Button size="s" onclick={() => copy(totpSecret, 'Ключ скопирован')}>Скопировать</Button>
			</div>
			<div>
				<label class="label" for="totp-code">Код из приложения — 6 цифр</label>
				<input
					id="totp-code"
					class="input num"
					inputmode="numeric"
					autocomplete="one-time-code"
					maxlength="7"
					bind:value={code}
				/>
			</div>
		</form>
	{/if}
	{#snippet footer()}
		{#if !recoveryCodes}
			<Button onclick={() => (totpOpen = false)}>Отмена</Button>
			<Button variant="primary" type="submit" form="totp-form">Включить</Button>
		{/if}
	{/snippet}
</Modal>

<Modal bind:open={regenOpen} title="Новые резервные коды">
	{#if recoveryCodes}
		{@const codes = recoveryCodes}
		{#await import('$lib/auth/RecoveryCodes.svelte') then m}
			<m.default {codes} ondone={() => ((regenOpen = false), (recoveryCodes = null))} />
		{/await}
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
		flex: none;
		display: block;
		line-height: 0;
		margin: 4px;
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
		gap: var(--s4);
		padding: var(--s5);
		margin-bottom: var(--s4);
	}
	.chapter {
		margin: var(--s6) 4px var(--s3);
		font-size: 12.5px;
		font-weight: 700;
		letter-spacing: 0.06em;
		text-transform: uppercase;
		color: var(--text-3);
	}
	.wrap {
		flex-wrap: wrap;
	}
	.qr {
		width: 210px;
		align-self: center;
		border-radius: var(--r);
	}
	.secret {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: 6px 10px;
	}
	.secret .hint {
		width: 100%;
	}
	.secret code {
		padding: 6px 10px;
		border-radius: 8px;
		background: var(--surface-2);
		font-size: 14px;
		letter-spacing: 0.04em;
		/* Переносим между группами по 4 знака, а не посреди группы. */
		overflow-wrap: anywhere;
		user-select: all;
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
