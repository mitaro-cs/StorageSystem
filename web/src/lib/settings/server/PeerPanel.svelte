<script lang="ts">
	import { onDestroy, onMount } from 'svelte';
	import { Copy, Laptop, MonitorSmartphone, RefreshCw, Crown, Unplug } from '@lucide/svelte';
	import { del, get, post } from '$lib/api';
	import { copy } from '$lib/copy';
	import { fmtAgo } from '$lib/format';
	import PullForm from '$lib/hosts/PullForm.svelte';
	import { session } from '$lib/session.svelte';
	import { toast, toastError } from '$lib/toasts.svelte';
	import { ask } from '$lib/ui/ask.svelte';
	import Button from '$lib/ui/Button.svelte';
	import type { PeerView } from './types';

	// Два компьютера хоста напрямую (0.8): главный — сайт для группы, второй — полная копия, изменения
	// из его окна уходят главному; главный пропал — второй становится главным сам.
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
				`«${p.name}» больше не будет получать данные сайта и не сможет стать главным. Подключить его снова можно по новому коду.`,
				{ title: 'Отключить компьютер?', ok: 'Отключить', danger: true }
			))
		)
			return;
		run(() => del(`/api/host/peers/${encodeURIComponent(p.computerId)}`));
	}

	async function makeMain() {
		if (
			!(await ask(
				'Сайт для группы будет работать здесь. Делайте так, только если главный компьютер не вернётся (сломался, его продали): если он включится, он увидит, что главный теперь этот, и станет вторым.',
				{ title: 'Сделать этот компьютер главным?', ok: 'Сделать главным', danger: true }
			))
		)
			return;
		run(() => post('/api/host/peers/main'));
	}

	const minutes = (ms: number) => Math.max(1, Math.round(ms / 60_000));
</script>

{#if v?.available}
	<h2 class="head">Второй компьютер</h2>
	{#if v.role === 'off'}
		<section class="card pane intro">
			<p class="lead">
				<MonitorSmartphone size={20} />
				<span
					>Дома — компьютер, в вузе — ноутбук: работать можно на обоих, данные одни. Сайт для группы
					работает на главном, второй держит полную копию, а если главный выключат — сам станет
					главным.</span
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
						><Crown size={16} /> Этот — главный: получить код</Button
					>
					{#if hostWindow}
						<Button onclick={() => (joining = true)}><Laptop size={16} /> Этот — второй</Button>
					{/if}
				</div>
			{:else}
				<p class="small muted">
					На главном компьютере нажмите «Этот — главный: получить код», а здесь введите адрес сайта
					и код. Данные этого компьютера заменятся данными сайта (прежние отложатся в папку данных).
				</p>
				<PullForm endpoint="/api/host/peers/join" label="Подключить" bind:busy={pulling} />
				{#if !pulling}
					<div>
						<Button size="s" variant="ghost" onclick={() => (joining = false)}>Отмена</Button>
					</div>
				{/if}
			{/if}
		</section>
	{:else if v.role === 'main'}
		<section class="card pane">
			<p class="lead">
				<Crown size={20} />
				<span><strong>Этот компьютер — главный.</strong> Сайт для группы работает здесь.</span>
			</p>
			{#if v.message}<p class="small warn">{v.message}</p>{/if}
			{#if others.length}
				<ul class="peers">
					{#each others as p (p.computerId)}
						<li>
							<Laptop size={18} />
							<span class="grow">
								<strong>{p.name}</strong>
								<span class="faint small"
									>{p.seenAt ? `на связи ${fmtAgo(p.seenAt)}` : 'ещё не выходил на связь'}</span
								>
							</span>
							<Button size="s" variant="ghost" label="Отключить" onclick={() => remove(p)}
								><Unplug size={15} /></Button
							>
						</li>
					{/each}
				</ul>
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
						Действует ещё {left} мин. На втором компьютере: «Управление → Сервер → Второй компьютер →
						Этот — второй» (или при первом запуске — «Подключить к сайту»).
					</p>
				</div>
			{:else}
				<div>
					<Button loading={busy} onclick={newCode}
						><Laptop size={16} />
						{others.length ? 'Подключить ещё компьютер' : 'Подключить второй компьютер'}</Button
					>
				</div>
			{/if}
		</section>
	{:else}
		<section class="card pane">
			<p class="lead">
				<Laptop size={20} />
				<span
					><strong>Это второй компьютер.</strong> Сайт для группы работает на главном{v.url
						? ` (${short(v.url)})`
						: ''}; изменения отсюда сразу уходят туда.</span
				>
			</p>
			<dl class="kv">
				<div>
					<dt>Связь с главным</dt>
					<dd class:bad={v.state !== 'ok'}>
						{#if v.state === 'ok'}{v.syncedAt
								? `синхронизировано ${fmtAgo(v.syncedAt)}`
								: 'на связи'}
						{:else if v.state === 'nobody'}главный не отвечает {minutes(v.nobodyFor)} мин — скоро этот
							станет главным
						{:else}{v.message ?? 'нет связи'}{/if}
					</dd>
				</div>
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
			{#if hostWindow}
				<div class="row wrap">
					<Button size="s" loading={busy} onclick={sync}
						><RefreshCw size={15} /> Синхронизировать сейчас</Button
					>
					<Button size="s" variant="ghost" onclick={makeMain}
						><Crown size={15} /> Сделать главным</Button
					>
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
