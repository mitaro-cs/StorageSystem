<script lang="ts">
	import { onDestroy, onMount } from 'svelte';
	import {
		ArrowDownToLine,
		Copy,
		KeyRound,
		Laptop,
		MonitorSmartphone,
		RefreshCw,
		Unplug
	} from '@lucide/svelte';
	import { del, get, post } from '$lib/api';
	import { copy } from '$lib/copy';
	import { fmtAgo } from '$lib/format';
	import PullForm from '$lib/hosts/PullForm.svelte';
	import { session } from '$lib/session.svelte';
	import { toast, toastError } from '$lib/toasts.svelte';
	import { ask } from '$lib/ui/ask.svelte';
	import Button from '$lib/ui/Button.svelte';
	import type { PeerView } from './types';

	// Два компьютера хоста (0.8.1 — равные): данные одни, сайт для группы работает на том, что
	// включён; выключили — через минуту переезжает на другой, а «Перенести сайт сюда» — когда угодно.
	let v = $state<PeerView | null>(null);
	let busy = $state(false);
	let joining = $state(false);
	let pulling = $state(false);
	let now = $state(Date.now());
	let timer: ReturnType<typeof setInterval> | undefined;

	async function load() {
		try {
			v = await get<PeerView>('/api/host/peers');
		} catch {
			/* не приложение хоста или нет прав — блока просто нет */
		}
		now = Date.now();
	}

	onMount(() => {
		load();
		timer = setInterval(load, 3000);
	});
	onDestroy(() => clearInterval(timer));

	const hostWindow = $derived(!!session.me?.hostWindow);
	const others = $derived(v?.peers.filter((p) => !p.here) ?? []);
	const short = (u: string) => u.replace(/^https?:\/\//, '');
	const left = $derived(v?.code ? Math.max(0, Math.ceil((v.code.expiresAt - now) / 60_000)) : 0);
	const here = $derived(v?.role === 'main');

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

	const newCode = () => run(() => post<PeerView>('/api/host/peers/code'));
	const sync = () =>
		run(async () => {
			await post('/api/host/peers/sync');
			toast('Синхронизировано', 'ok');
		});

	async function remove(p: PeerView['peers'][number]) {
		if (
			!(await ask(
				`«${p.name}» больше не будет получать данные сайта и не сможет его принять. Связать снова можно по новому коду.`,
				{ title: 'Отвязать компьютер?', ok: 'Отвязать', danger: true }
			))
		)
			return;
		run(() => del(`/api/host/peers/${encodeURIComponent(p.computerId)}`));
	}

	async function moveHere() {
		if (
			!(await ask(
				'Сайт для группы переедет на этот компьютер за несколько секунд, ничего не потеряется. Адрес для группы тот же.',
				{ title: 'Перенести сайт сюда?', ok: 'Перенести' }
			))
		)
			return;
		run(async () => {
			await post('/api/host/peers/here');
			toast('Сайт теперь работает на этом компьютере', 'ok');
		});
	}

	const seconds = (ms: number) => Math.max(0, Math.round((60_000 - ms) / 1000));
</script>

{#if v?.available}
	<h2 class="head">Два компьютера</h2>
	{#if v.role === 'off'}
		<section class="card pane intro">
			<p class="lead">
				<MonitorSmartphone size={20} />
				<span
					>Дома — компьютер, в вузе — ноутбук: оба равные, данные одни. Сайт для группы работает на
					том, что включён, а выключили его — через минуту переезжает на другой. Адрес для группы не
					меняется.</span
				>
			</p>
			{#if v.cloud}
				<p class="small muted">
					Сейчас включён перенос через облачную папку. Чтобы связать компьютеры напрямую, выключите
					его выше.
				</p>
			{:else if !joining}
				<div class="row wrap">
					<Button variant="primary" loading={busy} onclick={newCode}
						><KeyRound size={16} /> Показать код</Button
					>
					{#if hostWindow}
						<Button onclick={() => (joining = true)}><Laptop size={16} /> Ввести код</Button>
					{/if}
				</div>
				<p class="small faint">
					Код показывает компьютер, где сайт уже работает, вводят — на другом.
				</p>
			{:else}
				<p class="small muted">
					На компьютере, где сайт уже работает, нажмите «Показать код», а здесь введите адрес сайта
					и код. Данные этого компьютера заменятся данными сайта (прежние отложатся в папку данных).
				</p>
				<PullForm endpoint="/api/host/peers/join" label="Связать" bind:busy={pulling} />
				{#if !pulling}
					<div>
						<Button size="s" variant="ghost" onclick={() => (joining = false)}>Отмена</Button>
					</div>
				{/if}
			{/if}
		</section>
	{:else}
		<section class="card pane">
			<p class="where" class:wait={v.state === 'nobody'}>
				<span class="dot" aria-hidden="true"></span>
				{#if here}Группа сейчас на <strong>этом компьютере</strong>
				{:else if v.state === 'nobody'}Сайт не отвечает — через {seconds(v.nobodyFor)} с он заработает
					здесь
				{:else if v.serving}Группа сейчас на <strong>«{v.serving}»</strong>
				{:else}Группа сейчас на другом компьютере{/if}
			</p>
			{#if v.message && v.state !== 'nobody'}<p class="small warn">{v.message}</p>{/if}
			<ul class="peers">
				{#each v.peers as p (p.computerId)}
					<li>
						<Laptop size={18} />
						<span class="grow">
							<strong>{p.name}{p.here ? ' — этот' : ''}</strong>
							<span class="faint small"
								>{p.here
									? here
										? 'сайт работает здесь'
										: v.syncedAt
											? `данные свежие, ${fmtAgo(v.syncedAt)}`
											: 'держит копию'
									: p.seenAt
										? `на связи ${fmtAgo(p.seenAt)}`
										: here
											? 'ещё не выходил на связь'
											: ''}</span
							>
						</span>
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
			{#if v.code}
				<div class="code-card">
					<div class="line">
						<span class="label">Адрес сайта</span>
						<span class="val"
							><strong>{v.code.url ? short(v.code.url) : '—'}</strong>
							{#if v.code.url}<Button
									size="s"
									variant="ghost"
									label="Скопировать адрес"
									onclick={() => copy(short(v!.code!.url!))}><Copy size={15} /></Button
								>{/if}</span
						>
					</div>
					<div class="line">
						<span class="label">Код</span>
						<span class="val"
							><strong class="code num">{v.code.code}</strong>
							<Button
								size="s"
								variant="ghost"
								label="Скопировать код"
								onclick={() => copy(v!.code!.code)}><Copy size={15} /></Button
							></span
						>
					</div>
					<p class="small muted">
						Действует ещё {left} мин. На другом компьютере: «Управление → Сервер → Два компьютера → Ввести
						код» (или при первом запуске — «Сайт уже есть на другом компьютере?»).
					</p>
				</div>
			{/if}
			{#if hostWindow}
				<div class="row wrap">
					{#if !here}
						<Button size="s" variant="primary" loading={busy} onclick={moveHere}
							><ArrowDownToLine size={15} /> Перенести сайт сюда</Button
						>
						<Button size="s" loading={busy} onclick={sync}
							><RefreshCw size={15} /> Синхронизировать</Button
						>
					{:else if !v.code && !others.length}
						<Button size="s" loading={busy} onclick={newCode}
							><KeyRound size={15} /> Показать код</Button
						>
					{/if}
				</div>
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
	.where {
		display: flex;
		align-items: center;
		gap: 8px;
		margin: 0;
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
	.code-card {
		display: flex;
		flex-direction: column;
		gap: var(--s2);
		padding: var(--s3);
		border-radius: var(--r);
		background: var(--surface-2);
	}
	.line {
		display: flex;
		flex-direction: column;
		gap: 2px;
	}
	.val {
		display: flex;
		align-items: center;
		gap: 8px;
		min-width: 0;
	}
	.val strong {
		overflow-wrap: anywhere;
	}
	.code {
		font-size: 24px;
		letter-spacing: 0.08em;
	}
	.warn,
	.bad {
		color: var(--danger);
	}
	.kv {
		margin: 0;
	}
</style>
