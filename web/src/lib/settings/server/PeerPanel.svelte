<script lang="ts">
	import { onDestroy, onMount } from 'svelte';
	import {
		Copy,
		Crown,
		KeyRound,
		Laptop,
		MonitorSmartphone,
		RefreshCw,
		Unplug
	} from '@lucide/svelte';
	import { copy } from '$lib/copy';
	import { del, get, post } from '$lib/api';
	import { fmtAgo } from '$lib/format';
	import PeerJoin from '$lib/hosts/PeerJoin.svelte';
	import { session } from '$lib/session.svelte';
	import { toast, toastError } from '$lib/toasts.svelte';
	import { ask } from '$lib/ui/ask.svelte';
	import Button from '$lib/ui/Button.svelte';
	import type { PeerView } from './types';

	// Два компьютера (0.9.3): равные, без кодов. Второй находит сайт в сети или по адресу, здесь –
	// «Разрешить». Хост выбирают сами: «Сделать хостом» у любого компьютера, на любом из двух.
	let v = $state<PeerView | null>(null);
	let busy = $state(false);
	let joining = $state(false);
	let timer: ReturnType<typeof setInterval> | undefined;

	async function load() {
		try {
			v = await get<PeerView>('/api/host/peers');
		} catch {
			/* не приложение хоста или нет прав – блока просто нет */
		}
	}

	onMount(() => {
		load();
		timer = setInterval(load, 3000);
	});
	onDestroy(() => clearInterval(timer));

	const hostWindow = $derived(!!session.me?.hostWindow);
	const here = $derived(v?.role === 'main');
	// Хост: этот компьютер – если сайт здесь, иначе тот, кого назвал адрес сайта.
	const isHost = (p: PeerView['peers'][number]) =>
		p.here ? here : !here && !!v?.serving && p.name === v.serving;

	async function run<T>(action: () => Promise<T>) {
		busy = true;
		try {
			await action();
			await load();
		} catch (e) {
			toastError(e);
		} finally {
			busy = false;
		}
	}

	// «Проверить» (0.9.5): спросить адрес сайта сейчас, кто на самом деле отвечает группе.
	const check = () =>
		run(async () => {
			const r = await post<PeerView>('/api/host/peers/sync');
			v = r;
			if (r.state === 'conflict') toast('Два основных – выберите, какой оставить', 'error');
			else if (r.state === 'ok')
				toast(
					r.role === 'main'
						? 'Проверено: группа работает с этим компьютером'
						: `Проверено: основной – «${r.serving ?? 'другой компьютер'}», данные свежие`,
					'ok'
				);
			else toast(r.message ?? 'Нет связи с адресом сайта', 'error');
		});

	async function yieldTo() {
		if (
			!(await ask(
				`Группа сейчас работает с «${v?.rival}». Этот компьютер станет копией и возьмёт его данные; свои изменения окна отсюда отправит туда.`,
				{ title: `Оставить основным «${v?.rival}»?`, ok: 'Оставить тот' }
			))
		)
			return;
		run(async () => {
			await post('/api/host/peers/yield');
			toast('Этот компьютер теперь копия', 'ok');
		});
	}

	const answer = (id: string, allow: boolean) =>
		run(() => post(`/api/host/peers/requests/${encodeURIComponent(id)}`, { allow }));

	async function makeHost(p: PeerView['peers'][number]) {
		if (
			!(await ask(
				p.here
					? 'Сайт для группы переедет на этот компьютер за несколько секунд, ничего не потеряется. Адрес тот же.'
					: `Сайт для группы переедет на «${p.name}» – он заберёт его сам в течение минуты, если включён. Адрес тот же.`,
				{ title: 'Сделать основным?', ok: 'Сделать основным' }
			))
		)
			return;
		run(async () => {
			await post('/api/host/peers/host', { computer: p.computerId });
			toast(p.here ? 'Сайт теперь работает на этом компьютере' : `Переносим на «${p.name}»…`, 'ok');
		});
	}

	async function remove(p: PeerView['peers'][number]) {
		if (
			!(await ask(
				`«${p.name}» больше не будет получать данные сайта и не сможет стать основным. Подключить снова – «Подключить к сайту» на нём.`,
				{ title: 'Отвязать компьютер?', ok: 'Отвязать', danger: true }
			))
		)
			return;
		run(() => del(`/api/host/peers/${encodeURIComponent(p.computerId)}`));
	}

	let siteKey = $state('');
	const showKey = () =>
		run(async () => {
			siteKey = (await get<{ key: string }>('/api/host/peers/key')).key;
		});
	async function changeKey() {
		if (
			!(await ask(
				'Старым ключом больше нельзя будет подключить новый компьютер. Уже связанные компьютеры продолжат работать.',
				{ title: 'Сменить ключ сайта?', ok: 'Сменить' }
			))
		)
			return;
		run(async () => {
			siteKey = (await post<{ key: string }>('/api/host/peers/key')).key;
			toast('Ключ сменён', 'ok');
		});
	}

	// «проверено 4 с назад» – по часам этого компьютера; панель спрашивает сервер раз в 3 с.
	let tick = $state(Date.now());
	$effect(() => {
		const t = setInterval(() => (tick = Date.now()), 1000);
		return () => clearInterval(t);
	});
	const ago = (at: number) => {
		const s = Math.max(0, Math.round((tick - at) / 1000));
		return s < 60 ? `${s} с назад` : fmtAgo(at);
	};
	/** На связи – отвечал за последние 30 с. */
	const online = (p: PeerView['peers'][number]) =>
		p.here || (!!p.seenAt && tick - p.seenAt < 30_000) || isHost(p);

	const seconds = (ms: number) => Math.max(0, Math.round((60_000 - ms) / 1000));
</script>

{#snippet keyCard()}
	<div class="key-card">
		<p class="small">
			<strong>Ключ сайта</strong> – вставьте на другом компьютере в «Подключить к сайту». Он подключится
			сразу и запомнит его навсегда.
		</p>
		<code class="key">{siteKey}</code>
		<div class="row wrap">
			<Button size="s" variant="primary" onclick={() => copy(siteKey)}
				><Copy size={15} /> Скопировать</Button
			>
			<Button size="s" variant="ghost" onclick={changeKey}>Сменить ключ</Button>
		</div>
		<p class="faint small">Ключ даёт доступ ко всем данным сайта – не показывайте его другим.</p>
	</div>
{/snippet}

{#if v?.available}
	<h2 class="head">Два компьютера</h2>
	{#if v.requests?.length}
		<section class="card pane ask-card" aria-live="polite">
			{#each v.requests as r (r.id)}
				<div class="req">
					<Laptop size={20} />
					<span class="grow"
						><strong>«{r.name}»</strong> хочет подключиться к сайту и работать с ним вместе с этим компьютером.</span
					>
					<div class="row">
						<Button size="s" variant="primary" loading={busy} onclick={() => answer(r.id, true)}
							>Разрешить</Button
						>
						<Button size="s" variant="ghost" onclick={() => answer(r.id, false)}>Отклонить</Button>
					</div>
				</div>
			{/each}
		</section>
	{/if}
	{#if v.role === 'off'}
		<section class="card pane intro">
			<p class="lead">
				<MonitorSmartphone size={20} />
				<span
					>Дома – компьютер, в вузе – ноутбук: оба равные, данные одни. Основной – тот, кого вы
					выберете; выключили его – через минуту сайт сам заработает на другом. Адрес для группы не
					меняется.</span
				>
			</p>
			{#if v.cloud}
				<p class="small muted">
					Сейчас включён перенос через облачную папку. Чтобы связать компьютеры напрямую, выключите
					его выше.
				</p>
			{:else if joining}
				<PeerJoin />
				<div>
					<Button size="s" variant="ghost" onclick={() => (joining = false)}>Отмена</Button>
				</div>
			{:else}
				<p class="small muted">
					<strong>Этот компьютер – основной?</strong> Покажите ключ сайта и вставьте его на другом.
					<strong>Сайт уже работает на другом?</strong> «Подключить к сайту» и вставьте ключ оттуда.
				</p>
				{#if hostWindow}
					<div class="row wrap">
						<Button variant="primary" loading={busy} onclick={showKey}
							><KeyRound size={16} /> Ключ сайта</Button
						>
						<Button onclick={() => (joining = true)}>Подключить к сайту</Button>
					</div>
				{/if}
				{#if siteKey}{@render keyCard()}{/if}
			{/if}
		</section>
	{:else}
		<section class="card pane">
			<div class="where-row">
				<p
					class="where"
					class:wait={v.state === 'nobody' || (here && v.state !== 'ok')}
					class:bad={v.state === 'conflict'}
				>
					<span class="dot" aria-hidden="true"></span>
					<span>
						{#if v.state === 'conflict'}Два основных: группа сейчас работает с <strong
								>«{v.rival}»</strong
							>
						{:else if here && v.state === 'ok'}Основной – <strong>этот компьютер</strong>, сайт
							работает здесь
						{:else if here && v.state === 'checking'}Основной – <strong>этот компьютер</strong>,
							проверяем адрес сайта…
						{:else if here}Основной – <strong>этот компьютер</strong>, но адрес сайта сейчас не
							отвечает
						{:else if v.state === 'nobody'}Основной не отвечает – через {seconds(v.nobodyFor)} с основным
							станет этот компьютер
						{:else if v.serving}Основной – <strong>«{v.serving}»</strong>, изменения отсюда уходят
							туда
						{:else}Основной – другой компьютер, изменения отсюда уходят туда{/if}
					</span>
				</p>
				<span class="checked">
					{#if v.checkedAt}<span class="faint small live" title="Статус обновляется сам каждые 10 с"
							>проверено {ago(v.checkedAt)}</span
						>{/if}
					{#if hostWindow}
						<Button size="s" variant="ghost" loading={busy} onclick={check}
							><RefreshCw size={15} /> Проверить</Button
						>
					{/if}
				</span>
			</div>
			{#if v.state === 'conflict' && hostWindow}
				<div class="conflict">
					<p class="small">
						Оба компьютера считают себя основными, а группа ходит на «{v.rival}». Выберите, какой
						оставить – второй станет копией и возьмёт его данные.
					</p>
					<div class="row wrap">
						<Button size="s" variant="primary" loading={busy} onclick={yieldTo}
							>Оставить «{v.rival}»</Button
						>
						<Button
							size="s"
							loading={busy}
							onclick={() => {
								const me = v?.peers.find((p) => p.here);
								if (me) makeHost(me);
							}}>Сделать основным этот</Button
						>
					</div>
				</div>
			{:else if v.message && v.state !== 'nobody'}<p class="small warn">{v.message}</p>{/if}
			<ul class="peers">
				{#each v.peers as p (p.computerId)}
					<li>
						<span class="pc" class:on={online(p)} title={online(p) ? 'На связи' : 'Не на связи'}
							>{#if isHost(p)}<Crown size={18} />{:else}<Laptop size={18} />{/if}</span
						>
						<span class="grow">
							<strong
								>{p.name}{#if p.here}<span class="me">этот компьютер</span>{/if}</strong
							>
							<span class="faint small"
								>{isHost(p)
									? 'основной'
									: p.here
										? v.syncedAt
											? `копия, свежая ${fmtAgo(v.syncedAt)}`
											: 'копия'
										: p.seenAt
											? `копия, на связи ${fmtAgo(p.seenAt)}`
											: 'копия'}</span
							>
						</span>
						{#if hostWindow && !isHost(p) && (p.here || here)}
							<Button size="s" loading={busy} onclick={() => makeHost(p)}>Сделать основным</Button>
						{/if}
						{#if !p.here && here}
							<Button size="s" variant="ghost" label="Отвязать" onclick={() => remove(p)}
								><Unplug size={15} /></Button
							>
						{/if}
					</li>
				{/each}
			</ul>
			{#if v.queued || v.filesMissing}
				<dl class="kv">
					{#if v.queued}
						<div>
							<dt>Ждут отправки</dt>
							<dd class="num">{v.queued}</dd>
						</div>
					{/if}
					{#if v.filesMissing}
						<div>
							<dt>Файлы</dt>
							<dd class="num bad">не докачано: {v.filesMissing}</dd>
						</div>
					{/if}
				</dl>
			{/if}
			{#if hostWindow}
				{#if siteKey}{@render keyCard()}
				{:else}
					<div>
						<Button size="s" variant="ghost" loading={busy} onclick={showKey}
							><KeyRound size={15} /> Ключ сайта – подключить ещё компьютер</Button
						>
					</div>
				{/if}
			{/if}
		</section>
	{/if}
{/if}

<style>
	.head {
		margin: var(--s6) 0 var(--s3);
	}
	.pane {
		display: flex;
		flex-direction: column;
		gap: var(--s3);
	}
	.lead {
		display: flex;
		gap: 10px;
		margin: 0;
		align-items: flex-start;
	}
	.lead :global(svg) {
		flex: none;
		margin-top: 2px;
	}
	.checked {
		display: inline-flex;
		align-items: center;
		gap: 8px;
	}
	.live::before {
		content: '';
		display: inline-block;
		width: 6px;
		height: 6px;
		margin-right: 6px;
		border-radius: 50%;
		background: #1fa37a;
		vertical-align: 1px;
		animation: live 2s ease-in-out infinite;
	}
	@keyframes live {
		50% {
			opacity: 0.3;
		}
	}
	.pc {
		position: relative;
		display: inline-grid;
	}
	.pc::after {
		content: '';
		position: absolute;
		right: -3px;
		bottom: -2px;
		width: 8px;
		height: 8px;
		border: 2px solid var(--surface);
		border-radius: 50%;
		background: var(--text-3);
	}
	.pc.on::after {
		background: #1fa37a;
	}
	.where-row {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 8px;
		flex-wrap: wrap;
	}
	.where {
		display: flex;
		align-items: center;
		gap: 8px;
		margin: 0;
	}
	.where.bad .dot {
		background: var(--danger);
		box-shadow: 0 0 0 3px color-mix(in srgb, var(--danger) 22%, transparent);
	}
	.conflict {
		display: flex;
		flex-direction: column;
		gap: 8px;
		padding: 12px 14px;
		border: 1px solid color-mix(in srgb, var(--danger) 50%, transparent);
		border-radius: var(--r);
		background: color-mix(in srgb, var(--danger) 8%, transparent);
	}
	.conflict p {
		margin: 0;
	}
	.me {
		margin-left: 8px;
		padding: 2px 8px;
		border-radius: var(--r-full);
		background: var(--surface-2);
		color: var(--text-2);
		font-size: 12px;
		font-weight: 600;
		vertical-align: 2px;
	}
	.dot {
		width: 8px;
		height: 8px;
		flex: none;
		border-radius: 50%;
		background: var(--ok, #22a06b);
		box-shadow: 0 0 0 3px color-mix(in srgb, var(--ok, #22a06b) 22%, transparent);
	}
	.where.wait .dot {
		background: var(--warn, #d08a00);
		box-shadow: 0 0 0 3px color-mix(in srgb, var(--warn, #d08a00) 22%, transparent);
	}
	.ask-card {
		border: 1px solid var(--accent);
	}
	.req {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: 10px;
	}
	.req .grow {
		flex: 1 1 220px;
	}
	.key-card {
		display: flex;
		flex-direction: column;
		gap: var(--s2);
		padding: var(--s3);
		border-radius: var(--r);
		background: var(--surface-2);
	}
	.key-card p {
		margin: 0;
	}
	.key {
		display: block;
		padding: 8px 10px;
		border-radius: var(--r-sm, 8px);
		background: var(--surface);
		font-size: 12px;
		overflow-wrap: anywhere;
		user-select: all;
	}
	.peers {
		margin: 0;
		padding: 0;
		list-style: none;
		display: flex;
		flex-direction: column;
		gap: 6px;
	}
	.peers li {
		display: flex;
		align-items: center;
		gap: 10px;
		padding: 8px 10px;
		border-radius: var(--r);
		background: var(--surface-2);
	}
	.peers .grow {
		display: flex;
		flex-direction: column;
		flex: 1;
		min-width: 0;
	}
	.warn,
	.bad {
		color: var(--danger);
	}
	.kv {
		margin: 0;
	}
</style>
