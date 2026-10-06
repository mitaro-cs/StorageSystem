<script lang="ts">
	import { onDestroy, onMount } from 'svelte';
	import { Crown, Laptop, MonitorSmartphone, RefreshCw, Unplug } from '@lucide/svelte';
	import { del, get, post } from '$lib/api';
	import { fmtAgo } from '$lib/format';
	import PeerJoin from '$lib/hosts/PeerJoin.svelte';
	import { session } from '$lib/session.svelte';
	import { toast, toastError } from '$lib/toasts.svelte';
	import { ask } from '$lib/ui/ask.svelte';
	import Button from '$lib/ui/Button.svelte';
	import type { PeerView } from './types';

	// Два компьютера (0.9.3): равные, без кодов. Второй находит сайт в сети или по адресу, здесь —
	// «Разрешить». Хост выбирают сами: «Сделать хостом» у любого компьютера, на любом из двух.
	let v = $state<PeerView | null>(null);
	let busy = $state(false);
	let joining = $state(false);
	let timer: ReturnType<typeof setInterval> | undefined;

	async function load() {
		try {
			v = await get<PeerView>('/api/host/peers');
		} catch {
			/* не приложение хоста или нет прав — блока просто нет */
		}
	}

	onMount(() => {
		load();
		timer = setInterval(load, 3000);
	});
	onDestroy(() => clearInterval(timer));

	const hostWindow = $derived(!!session.me?.hostWindow);
	const here = $derived(v?.role === 'main');
	// Хост: этот компьютер — если сайт здесь, иначе тот, кого назвал адрес сайта.
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

	const sync = () =>
		run(async () => {
			await post('/api/host/peers/sync');
			toast('Синхронизировано', 'ok');
		});

	const answer = (id: string, allow: boolean) =>
		run(() => post(`/api/host/peers/requests/${encodeURIComponent(id)}`, { allow }));

	async function makeHost(p: PeerView['peers'][number]) {
		if (
			!(await ask(
				p.here
					? 'Сайт для группы переедет на этот компьютер за несколько секунд, ничего не потеряется. Адрес тот же.'
					: `Сайт для группы переедет на «${p.name}» — он заберёт его сам в течение минуты, если включён. Адрес тот же.`,
				{ title: 'Сделать хостом?', ok: 'Сделать хостом' }
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
				`«${p.name}» больше не будет получать данные сайта и не сможет стать хостом. Подключить снова — «Подключить к сайту» на нём.`,
				{ title: 'Отвязать компьютер?', ok: 'Отвязать', danger: true }
			))
		)
			return;
		run(() => del(`/api/host/peers/${encodeURIComponent(p.computerId)}`));
	}

	const seconds = (ms: number) => Math.max(0, Math.round((60_000 - ms) / 1000));
</script>

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
					>Дома — компьютер, в вузе — ноутбук: оба равные, данные одни. Хост — тот, кого вы
					выберете; выключили его — через минуту сайт сам переедет на другой. Адрес для группы не
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
					<strong>Сайт уже работает на другом компьютере?</strong> Подключите этот к нему — кода не
					нужно. <strong>Сайт работает здесь?</strong> Нажмите «Подключить к сайту» на другом компьютере
					— здесь спросят «Разрешить?».
				</p>
				{#if hostWindow}
					<div>
						<Button variant="primary" onclick={() => (joining = true)}>Подключить к сайту</Button>
					</div>
				{/if}
			{/if}
		</section>
	{:else}
		<section class="card pane">
			<p class="where" class:wait={v.state === 'nobody'}>
				<span class="dot" aria-hidden="true"></span>
				{#if here}Хост — <strong>этот компьютер</strong>, группа работает здесь
				{:else if v.state === 'nobody'}Хост не отвечает — через {seconds(v.nobodyFor)} с хостом станет
					этот компьютер
				{:else if v.serving}Хост — <strong>«{v.serving}»</strong>, здесь копия
				{:else}Хост — другой компьютер, здесь копия{/if}
			</p>
			{#if v.message && v.state !== 'nobody'}<p class="small warn">{v.message}</p>{/if}
			<ul class="peers">
				{#each v.peers as p (p.computerId)}
					<li>
						{#if isHost(p)}<Crown size={18} />{:else}<Laptop size={18} />{/if}
						<span class="grow">
							<strong>{p.name}{p.here ? ' — этот' : ''}</strong>
							<span class="faint small"
								>{isHost(p)
									? 'хост'
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
							<Button size="s" loading={busy} onclick={() => makeHost(p)}>Сделать хостом</Button>
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
			{#if hostWindow && !here}
				<div>
					<Button size="s" variant="ghost" loading={busy} onclick={sync}
						><RefreshCw size={15} /> Синхронизировать</Button
					>
				</div>
			{/if}
			{#if here}
				<p class="faint small">
					Ещё компьютер — на нём «Подключить к сайту», здесь спросят «Разрешить?».
				</p>
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
