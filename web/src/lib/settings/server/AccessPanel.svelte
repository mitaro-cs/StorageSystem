<script lang="ts">
	import {
		Cloud,
		Copy,
		ExternalLink,
		Globe,
		Link2,
		LogIn,
		Maximize2,
		PowerOff,
		Wifi
	} from '@lucide/svelte';
	import { onMount } from 'svelte';
	import { get, post, put } from '$lib/api';
	import { copy } from '$lib/copy';
	import { loadMe } from '$lib/session.svelte';
	import { toast, toastError } from '$lib/toasts.svelte';
	import Button from '$lib/ui/Button.svelte';
	import QrScreen from '$lib/ui/QrScreen.svelte';
	import { waitForRestart } from './restart';
	import type { Access, AccessMode } from './types';

	let a = $state<Access | null>(null);
	let pick = $state<AccessMode | null>(null);
	let sub = $state('');
	let manual = $state('');
	let check = $state<{ available: boolean; reason: string } | null>(null);
	let busy = $state(false);
	let restarting = $state(false);
	let qr = $state(false);
	// CloudPub: вход почтой и паролем или ключом API (если вход в CloudPub — через Яндекс или VK).
	let cpEmail = $state('');
	let cpPassword = $state('');
	let cpToken = $state('');
	let cpWithToken = $state(false);

	async function load() {
		try {
			a = await get<Access>('/api/admin/access');
		} catch (e) {
			toastError(e);
			return;
		}
		pick ??= a.mode === 'off' ? 'cloudpub' : a.mode;
		if (!sub) sub = a.fxtunnel.subdomain ?? a.fxtunnel.suggested;
		if (!manual) manual = a.manualUrl ?? '';
	}
	onMount(load);

	// Пока идёт вход или подключение — обновляем состояние.
	$effect(() => {
		if (!a) return;
		const waiting = a.state === 'starting' || a.state === 'retrying' || !!a.fxtunnel.login;
		if (!waiting) return;
		const id = setInterval(load, 1500);
		return () => clearInterval(id);
	});

	async function run(action: () => Promise<Access>): Promise<boolean> {
		busy = true;
		try {
			a = await action();
			return true;
		} catch (e) {
			toastError(e);
			return false;
		} finally {
			busy = false;
		}
	}

	const login = () => run(() => post<Access>('/api/admin/access/fxtunnel/login', {}));

	async function cloudpubLogin(e: SubmitEvent) {
		e.preventDefault();
		const body = cpWithToken ? { token: cpToken } : { email: cpEmail, password: cpPassword };
		if (await run(() => post<Access>('/api/admin/access/cloudpub/login', body))) {
			cpPassword = cpToken = '';
			toast('Вход в CloudPub выполнен', 'ok');
		}
	}

	async function cloudpubLogout() {
		if (await run(() => post<Access>('/api/admin/access/cloudpub/logout', {}))) await loadMe();
	}

	async function logout() {
		if (await run(() => post<Access>('/api/admin/access/fxtunnel/logout', {}))) await loadMe();
	}

	async function enable(mode: AccessMode) {
		const wasLan = a?.mode === 'lan';
		const body =
			mode === 'fxtunnel'
				? { mode, subdomain: sub }
				: mode === 'manual'
					? { mode, url: manual }
					: { mode };
		// fxTunnel и CloudPub подключаются несколько секунд — состояние обновится само.
		if (!(await run(() => put<Access>('/api/admin/access', body)))) return;
		await loadMe();
		if (mode === 'lan' || wasLan) {
			// Сервер начнёт или перестанет слушать сеть — приложение его перезапустит.
			restarting = true;
			await waitForRestart();
			return;
		}
		if (mode === 'off') toast('Доступ выключен', 'ok');
		if (mode === 'manual') toast('Адрес сохранён', 'ok');
	}

	let checkTimer: ReturnType<typeof setTimeout> | undefined;
	function onSub(value: string) {
		sub = value.toLowerCase().replace(/[^a-z0-9-]/g, '');
		check = null;
		clearTimeout(checkTimer);
		if (!a?.fxtunnel.loggedIn || sub.length < 3) return;
		checkTimer = setTimeout(async () => {
			try {
				check = await post('/api/admin/access/fxtunnel/check', { subdomain: sub });
			} catch {
				check = null;
			}
		}, 500);
	}

	const tone = $derived(
		!a
			? 'off'
			: a.state === 'online'
				? 'ok'
				: a.state === 'off'
					? 'off'
					: a.state === 'error'
						? 'bad'
						: 'wait'
	);
	const title = $derived(
		!a
			? ''
			: {
					off: 'Сайт открыт только на этом компьютере',
					online: 'Группа видит сайт',
					starting: 'Открываем доступ…',
					retrying: 'Нет связи с туннелем',
					error: 'Доступ не открыт',
					needs_login: a.mode === 'cloudpub' ? 'Нужно войти в CloudPub' : 'Нужно войти в fxTunnel'
				}[a.state]
	);
	const hint = $derived(
		!a
			? ''
			: (a.message ??
					(a.state === 'online'
						? a.mode === 'lan'
							? 'Откроется с телефонов в той же сети Wi‑Fi'
							: 'Отправьте ссылку в чат группы или покажите QR-код'
						: a.state === 'off'
							? 'Выберите ниже, как одногруппники будут заходить на сайт'
							: ''))
	);

	const modes = $derived(
		[
			{
				value: 'cloudpub' as const,
				icon: Cloud,
				label: 'Интернет — CloudPub',
				badge: 'бесплатно',
				hint: 'Работает с VPN и без, в любой Wi‑Fi: только обычный HTTPS',
				show: true
			},
			{
				value: 'fxtunnel' as const,
				icon: Globe,
				label: 'Интернет — fxTunnel',
				badge: 'бесплатно',
				hint: 'Свой адрес вида имя.fxtun.ru',
				show: true
			},
			{
				value: 'lan' as const,
				icon: Wifi,
				label: 'Локальная сеть',
				badge: '',
				hint: 'Одна Wi‑Fi: аудитория или общежитие',
				show: !!a?.lan.available
			},
			{
				value: 'manual' as const,
				icon: Link2,
				label: 'Свой адрес',
				badge: '',
				hint: 'Есть домен или сервер — для опытных',
				show: true
			},
			{
				value: 'off' as const,
				icon: PowerOff,
				label: 'Выключить',
				badge: '',
				hint: 'Сайт только на этом компьютере',
				show: true
			}
		].filter((m) => m.show)
	);
</script>

{#if restarting}
	<section class="card restarting" role="status">
		<span class="spinner" aria-hidden="true"></span>
		<div>
			<strong>Перезапускаем сервер…</strong>
			<p class="small muted">Несколько секунд — страница обновится сама.</p>
		</div>
	</section>
{:else if a}
	<section class="card access">
		<div class="status {tone}">
			<span class="dot" aria-hidden="true"></span>
			<div>
				<strong>{title}</strong>
				{#if hint}<p class="small muted">{hint}</p>{/if}
			</div>
		</div>
		{#if a.url && (a.state === 'online' || a.state === 'retrying')}
			<div class="address">
				<code>{a.url}</code>
				<div class="row wrap">
					<Button size="s" variant="primary" onclick={() => copy(a!.url!, 'Ссылка скопирована')}
						><Copy size={15} /> Скопировать</Button
					>
					<Button size="s" onclick={() => (qr = true)}><Maximize2 size={15} /> QR-код</Button>
				</div>
			</div>
		{/if}
	</section>

	{#if a.fixed}
		<p class="tip small">
			<span>Адрес сайта задан в настройках сервера (base-url) — меняйте его там.</span>
		</p>
	{:else}
		<h3 class="sub">Как одногруппники попадают на сайт</h3>
		<div class="choices" role="radiogroup" aria-label="Способ доступа">
			{#each modes as m (m.value)}
				{@const Icon = m.icon}
				<button
					role="radio"
					aria-checked={pick === m.value}
					class:on={pick === m.value}
					onclick={() => (pick = m.value)}
				>
					<span class="head"
						><Icon size={18} /> <strong>{m.label}</strong>
						{#if m.badge}<span class="badge">{m.badge}</span>{/if}
						{#if a.mode === m.value}<span class="badge now">сейчас</span>{/if}</span
					>
					<span class="small">{m.hint}</span>
				</button>
			{/each}
		</div>

		<section class="card detail">
			{#if pick === 'cloudpub'}
				{#if !a.cloudpub.loggedIn}
					<p>
						CloudPub — российский сервис: даёт сайту с этого компьютера постоянную ссылку вида
						<code>слово-слово-слово.cloudpub.ru</code>. Связь идёт через обычный HTTPS, поэтому
						ссылка открывается и с включённым VPN, и без него, и в Wi‑Fi вуза или общежития.
					</p>
					<p class="small">
						Нет аккаунта?
						<a href="https://cloudpub.ru/auth/sign-up/" target="_blank" rel="noreferrer"
							>Зарегистрируйтесь на cloudpub.ru</a
						> — это бесплатно, затем войдите здесь.
					</p>
					<form class="stack" onsubmit={cloudpubLogin}>
						{#if cpWithToken}
							<div>
								<label class="label" for="cp-token">Ключ API</label>
								<input
									id="cp-token"
									class="input"
									bind:value={cpToken}
									autocomplete="off"
									spellcheck="false"
									required
								/>
								<p class="small muted">Личный кабинет CloudPub → «Ключ API» → скопировать.</p>
							</div>
						{:else}
							<div>
								<label class="label" for="cp-email">Почта от CloudPub</label>
								<input
									id="cp-email"
									class="input"
									type="email"
									bind:value={cpEmail}
									autocomplete="username"
									required
								/>
							</div>
							<div>
								<label class="label" for="cp-password">Пароль от CloudPub</label>
								<input
									id="cp-password"
									class="input"
									type="password"
									bind:value={cpPassword}
									autocomplete="current-password"
									required
								/>
								<p class="small muted">Пароль передаётся клиенту CloudPub и не сохраняется.</p>
							</div>
						{/if}
						<div class="row wrap">
							<Button type="submit" variant="primary" loading={busy}
								><LogIn size={16} /> Войти в CloudPub</Button
							>
							<Button variant="ghost" onclick={() => (cpWithToken = !cpWithToken)}
								>{cpWithToken
									? 'Войти почтой и паролем'
									: 'Вход через Яндекс или VK? Ключ API'}</Button
							>
						</div>
					</form>
				{:else}
					{#if a.cloudpub.url}
						<p>Постоянный адрес: <code>{a.cloudpub.url}</code></p>
					{:else}
						<p>Адрес выдаст CloudPub при первом подключении — он больше не изменится.</p>
					{/if}
					<div class="row wrap">
						{#if a.mode !== 'cloudpub'}
							<Button variant="primary" loading={busy} onclick={() => enable('cloudpub')}
								><Cloud size={16} /> Открыть доступ через CloudPub</Button
							>
						{/if}
						<Button variant="ghost" onclick={cloudpubLogout}>Выйти из CloudPub</Button>
					</div>
				{/if}
				<p class="faint small">
					CloudPub — российский сервис (<a
						href="https://github.com/ermak-dev/cloudpub"
						target="_blank"
						rel="noreferrer">открытый клиент</a
					>, серверы в Москве). Через него идёт трафик, как через любого провайдера; данные группы
					хранятся только на этом компьютере.
				</p>
			{:else if pick === 'fxtunnel'}
				{#if !a.fxtunnel.loggedIn}
					{#if a.fxtunnel.login}
						<ol class="steps">
							<li>
								Откройте страницу fxTunnel и войдите — почтой или через соцсеть.
								<div>
									<Button
										href={a.fxtunnel.login.url}
										target="_blank"
										variant="primary"
										size="s"
										label="Открыть страницу fxTunnel"
										><ExternalLink size={15} /> Открыть страницу fxTunnel</Button
									>
								</div>
							</li>
							<li>
								Введите там этот код:
								<div class="code-row">
									<code class="code">{a.fxtunnel.login.code}</code>
									<Button size="s" onclick={() => copy(a!.fxtunnel.login!.code, 'Код скопирован')}
										><Copy size={15} /> Скопировать</Button
									>
								</div>
							</li>
						</ol>
						<p class="small muted waiting">
							<span class="spinner small-spin" aria-hidden="true"></span> Ждём подтверждения…
						</p>
					{:else}
						<p>
							fxTunnel — бесплатный сервис: он даёт сайту с этого компьютера постоянную ссылку,
							которая открывается с любого телефона. Нужен аккаунт — вход займёт минуту.
						</p>
						<div>
							<Button variant="primary" loading={busy} onclick={login}
								><LogIn size={16} /> Войти в fxTunnel</Button
							>
						</div>
					{/if}
				{:else}
					<label class="label" for="fx-sub">Адрес сайта</label>
					<div class="sub-input">
						<input
							id="fx-sub"
							class="input"
							value={sub}
							oninput={(e) => onSub(e.currentTarget.value)}
							maxlength="32"
							autocomplete="off"
							spellcheck="false"
						/>
						<span class="suffix">.{a.fxtunnel.domain}</span>
					</div>
					{#if check}
						<p class="small" class:ok={check.available} class:bad={!check.available}>
							{check.available
								? 'Адрес свободен'
								: `Адрес занят${check.reason ? `: ${check.reason}` : ''}`}
						</p>
					{:else}
						<p class="small muted">
							Мы предложили адрес со случайным хвостом, чтобы его не заняли. Можно оставить как
							есть.
						</p>
					{/if}
					<div class="row wrap">
						{#if a.mode !== 'fxtunnel' || sub !== a.fxtunnel.subdomain}
							<Button
								variant="primary"
								loading={busy}
								disabled={sub.length < 3 || check?.available === false}
								onclick={() => enable('fxtunnel')}
								><Globe size={16} />
								{a.mode === 'fxtunnel' ? 'Сменить адрес' : 'Открыть доступ'}</Button
							>
						{/if}
						<Button variant="ghost" onclick={logout}>Выйти из fxTunnel</Button>
					</div>
				{/if}
				<p class="tip amber small">
					<span
						>Бесплатный адрес не закреплён за вами: если компьютер долго выключен, его может занять
						другой пользователь fxTunnel. Для постоянной группы закрепите адрес в личном кабинете
						fxTunnel (тариф Base) — или используйте свой домен.</span
					>
				</p>
				<p class="faint small">
					fxTunnel — открытый проект
					<a href="https://github.com/mephistofox/fxtun.dev" target="_blank" rel="noreferrer"
						>github.com/mephistofox/fxtun.dev</a
					>. Через его сервер идёт трафик, как через любого провайдера; данные группы хранятся
					только на этом компьютере.
				</p>
			{:else if pick === 'lan'}
				<p>
					Сайт откроется с телефонов и ноутбуков, подключённых к той же сети Wi‑Fi, что и этот
					компьютер. Без интернета-туннеля не будет установки на экран «Домой» и офлайн-режима.
				</p>
				{#if a.lan.urls.length}
					<ul class="urls">
						{#each a.lan.urls as u (u)}<li><code>{u}</code></li>{/each}
					</ul>
				{:else}
					<p class="small muted">Компьютер сейчас не подключён к локальной сети.</p>
				{/if}
				{#if a.mode !== 'lan'}
					<div>
						<Button variant="primary" loading={busy} onclick={() => enable('lan')}
							><Wifi size={16} /> Открыть в локальной сети</Button
						>
					</div>
					<p class="faint small">Приложение перезапустит сервер — это займёт несколько секунд.</p>
				{/if}
			{:else if pick === 'manual'}
				<p>
					Если у вас есть свой домен, сервер или платный туннель, который ведёт на этот компьютер,
					укажите адрес — по нему будут строиться ссылки-приглашения и QR-коды.
				</p>
				<label class="label" for="manual-url">Адрес сайта</label>
				<input
					id="manual-url"
					class="input"
					type="url"
					placeholder="https://group.example.ru"
					bind:value={manual}
				/>
				<div>
					<Button
						variant="primary"
						loading={busy}
						disabled={!manual.trim()}
						onclick={() => enable('manual')}>Сохранить адрес</Button
					>
				</div>
			{:else if pick === 'off'}
				<p>
					Сайт будет виден только на этом компьютере. У одногруппников останутся сохранённые данные
					— их можно смотреть без связи, а сделанное отправится, когда доступ снова откроется.
				</p>
				{#if a.mode !== 'off'}
					<div>
						<Button variant="danger" loading={busy} onclick={() => enable('off')}
							><PowerOff size={16} /> Выключить доступ</Button
						>
					</div>
				{/if}
			{/if}
		</section>
	{/if}

	{#if a.url}
		<QrScreen
			bind:open={qr}
			value={a.url}
			title="Сайт группы"
			hint="Отсканируйте камерой телефона"
		/>
	{/if}
{/if}

<style>
	.access {
		display: flex;
		flex-direction: column;
		gap: var(--s3);
	}
	.status {
		display: flex;
		align-items: flex-start;
		gap: 12px;
	}
	.status strong {
		font-size: 17px;
	}
	.dot {
		flex: none;
		width: 12px;
		height: 12px;
		margin-top: 6px;
		border-radius: 50%;
		background: var(--text-3);
	}
	.ok .dot {
		background: var(--ok);
		box-shadow: 0 0 0 4px var(--ok-soft);
	}
	.wait .dot {
		background: var(--amber);
		box-shadow: 0 0 0 4px var(--amber-soft);
		animation: pulse 1.4s ease-in-out infinite;
	}
	.bad .dot {
		background: var(--danger);
	}
	@keyframes pulse {
		50% {
			opacity: 0.4;
		}
	}
	.address {
		display: flex;
		flex-direction: column;
		gap: 10px;
		padding: 14px;
		border-radius: var(--r);
		background: var(--surface-2);
	}
	.address code {
		font-size: 16px;
		font-weight: 600;
		word-break: break-all;
	}
	.sub {
		margin: var(--s5) 0 var(--s3);
	}
	.choices {
		display: grid;
		grid-template-columns: repeat(2, 1fr);
		gap: 8px;
		margin-bottom: var(--s3);
	}
	.choices button {
		display: flex;
		flex-direction: column;
		align-items: flex-start;
		gap: 4px;
		padding: 14px 16px;
		border: 1px solid var(--border);
		border-radius: var(--r);
		background: var(--surface);
		color: var(--text);
		text-align: left;
	}
	.choices .head {
		display: flex;
		align-items: center;
		flex-wrap: wrap;
		gap: 6px;
	}
	.choices button > .small {
		color: var(--text-3);
	}
	.choices button.on {
		background: var(--accent);
		border-color: var(--accent);
		color: var(--accent-text);
	}
	.choices button.on > .small {
		color: inherit;
		opacity: 0.75;
	}
	.badge {
		padding: 1px 8px;
		border-radius: var(--r-full);
		background: var(--ok-soft);
		color: var(--ok);
		font-size: 12px;
		font-weight: 650;
	}
	.badge.now {
		background: var(--surface-2);
		color: var(--text-2);
	}
	.on .badge {
		background: color-mix(in srgb, var(--accent-text) 18%, transparent);
		color: inherit;
	}
	.detail {
		display: flex;
		flex-direction: column;
		gap: var(--s3);
	}
	.steps {
		margin: 0;
		padding-left: 22px;
		display: flex;
		flex-direction: column;
		gap: var(--s3);
	}
	.steps li > div {
		margin-top: 8px;
	}
	.code-row {
		display: flex;
		align-items: center;
		flex-wrap: wrap;
		gap: 10px;
	}
	.code {
		padding: 8px 14px;
		border-radius: var(--r);
		background: var(--surface-2);
		font-size: 22px;
		font-weight: 700;
		letter-spacing: 0.12em;
	}
	.waiting {
		display: flex;
		align-items: center;
		gap: 8px;
	}
	.sub-input {
		display: flex;
		align-items: center;
		gap: 6px;
	}
	.sub-input .input {
		flex: 1;
		min-width: 0;
		font-family: var(--mono, ui-monospace, monospace);
	}
	.suffix {
		flex: none;
		color: var(--text-2);
		font-weight: 600;
	}
	.ok {
		color: var(--ok);
	}
	.bad {
		color: var(--danger);
	}
	.urls {
		margin: 0;
		padding-left: 20px;
	}
	.restarting {
		display: flex;
		align-items: center;
		gap: var(--s3);
	}
	.spinner {
		flex: none;
		width: 22px;
		height: 22px;
		border: 3px solid var(--border);
		border-top-color: var(--text);
		border-radius: 50%;
		animation: spin 0.8s linear infinite;
	}
	.small-spin {
		width: 14px;
		height: 14px;
		border-width: 2px;
	}
	@keyframes spin {
		to {
			transform: rotate(360deg);
		}
	}
	@media (max-width: 520px) {
		.choices {
			grid-template-columns: 1fr;
		}
	}
</style>
