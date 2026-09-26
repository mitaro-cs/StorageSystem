<script lang="ts">
	import { Fingerprint, KeyRound, MonitorSmartphone, ScanLine, ShieldCheck } from '@lucide/svelte';
	import { get, post } from '$lib/api';
	import { copy } from '$lib/copy';
	import { loadMe, session } from '$lib/session.svelte';
	import { toast, toastError } from '$lib/toasts.svelte';
	import { askText } from '$lib/ui/ask.svelte';
	import Button from '$lib/ui/Button.svelte';
	import Modal from '$lib/ui/Modal.svelte';

	// Вход и безопасность: пароль, двухфакторная защита с резервными кодами, ключи входа и вход
	// на другом устройстве по QR-коду.
	const me = $derived(session.me!);

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
	let scanOpen = $state(false);

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
</script>

<section class="card pane">
	<div class="pane-title">
		<KeyRound size={18} />
		<h3>Пароль</h3>
	</div>
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
		<!-- Поля нового пароля с «Придумать за меня» (словарь) — отдельным кусочком. -->
		{#await import('$lib/auth/PasswordFields.svelte') then m}
			<m.default bind:password bind:confirm />
		{/await}
		<div><Button type="submit">Сменить пароль</Button></div>
	</form>
</section>

<section class="card pane">
	<div class="pane-title">
		<ShieldCheck size={18} />
		<h3>Двухфакторная защита</h3>
		<span class="chip" class:ok={me.user.totpEnabled}
			>{me.user.totpEnabled ? 'включена' : 'выключена'}</span
		>
	</div>
	<p class="muted small">
		При входе с нового устройства — ещё и код из приложения на телефоне. Если телефон потеряется,
		войти помогут резервные коды.
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
			{#if !me.user.instanceRole || !me.instance.requireStaffTotp}<Button
					variant="ghost"
					onclick={disableTotp}>Отключить</Button
				>{/if}
		{:else}
			<Button variant="primary" onclick={startTotp}>Включить</Button>
		{/if}
	</div>
</section>

{#if !me.hostWindow}
	<section class="card pane">
		<div class="pane-title">
			<Fingerprint size={18} />
			<h3>Вход по отпечатку или лицу</h3>
		</div>
		<p class="muted small">
			Ключ на телефоне или ноутбуке вместо пароля и кода: приложили палец — и вы вошли. Подделать
			или выманить его нельзя.
		</p>
		{#await import('$lib/auth/PasskeysPanel.svelte') then m}<m.default />{/await}
	</section>
{/if}

<section class="card pane">
	<div class="pane-title">
		<MonitorSmartphone size={18} />
		<h3>Вход на другом устройстве</h3>
	</div>
	<p class="muted small">
		На ноутбуке или втором телефоне откройте groupbase, выберите «По QR-коду» и отсканируйте код
		отсюда — логин и пароль вводить не придётся.
	</p>
	<div>
		<Button onclick={() => (scanOpen = true)}><ScanLine size={17} /> Сканировать QR-код</Button>
	</div>
</section>
{#if scanOpen}
	{#await import('$lib/auth/QrScanner.svelte') then m}<m.default bind:open={scanOpen} />{/await}
{/if}

<!-- Редкие окна (2FA, резервные коды) грузят свой код только при открытии. -->
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

<style>
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
</style>
