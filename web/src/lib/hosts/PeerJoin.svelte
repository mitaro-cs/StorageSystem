<script lang="ts">
	import { onDestroy, onMount } from 'svelte';
	import { Laptop, RefreshCw, Wifi } from '@lucide/svelte';
	import { request } from '$lib/api';
	import { waitForRestart } from '$lib/settings/server/restart';
	import Button from '$lib/ui/Button.svelte';

	// Подключить этот компьютер к сайту без кода (0.9.3): сайт в локальной сети находится сам, или
	// вводят его адрес; на компьютере с сайтом всплывает «Разрешить?». setupCode — на первом запуске.
	let { setupCode = '', busy = $bindable(false) }: { setupCode?: string; busy?: boolean } =
		$props();

	interface Found {
		site: string;
		computer: string;
		name: string;
		url: string;
	}
	interface Ask {
		phase: 'idle' | 'asking' | 'waiting' | 'joining' | 'error';
		message: string | null;
		url: string | null;
	}

	const q = () => (setupCode ? `?code=${encodeURIComponent(setupCode)}` : '');
	const discoverUrl = () =>
		setupCode ? `/api/setup/peer/discover${q()}` : '/api/host/peers/discover';
	const askUrl = () => (setupCode ? `/api/setup/peer/ask${q()}` : '/api/host/peers/ask');

	let found = $state<Found[] | null>(null);
	let searching = $state(false);
	let url = $state('');
	let ask = $state<Ask | null>(null);
	let error = $state('');
	let target = $state('');
	let timer: ReturnType<typeof setInterval> | undefined;
	onDestroy(() => clearInterval(timer));

	async function search() {
		searching = true;
		try {
			found = await request<Found[]>(discoverUrl(), { anonymous: true });
		} catch {
			found = [];
		} finally {
			searching = false;
		}
	}
	onMount(search);

	async function status(): Promise<Ask> {
		if (setupCode) return await request<Ask>(askUrl(), { anonymous: true });
		return (await request<{ ask: Ask }>('/api/host/peers', { quiet401: true })).ask;
	}

	async function connect(siteUrl: string, name: string) {
		error = '';
		target = name;
		busy = true;
		try {
			ask = await request<Ask>(askUrl(), {
				method: 'POST',
				body: { url: siteUrl },
				anonymous: true
			});
			clearInterval(timer);
			timer = setInterval(async () => {
				try {
					ask = await status();
				} catch {
					// Сервер перезапускается с данными сайта — это и есть успех.
					if (ask?.phase === 'joining') {
						clearInterval(timer);
						await waitForRestart();
					}
					return;
				}
				if (ask.phase === 'error') {
					clearInterval(timer);
					error = ask.message ?? 'Не получилось';
					busy = false;
				} else if (ask.phase === 'joining' && ask.message) {
					clearInterval(timer);
					await waitForRestart();
				}
			}, 1000);
		} catch (e) {
			error = e instanceof Error ? e.message : 'Не получилось';
			busy = false;
		}
	}

	function submit(e: SubmitEvent) {
		e.preventDefault();
		if (url.trim()) connect(url.trim(), '');
	}

	const host = (u: string) => u.replace(/^https?:\/\//, '').replace(/\/$/, '');
</script>

{#if busy && ask}
	<div class="state" role="status">
		<span class="spinner" aria-hidden="true"></span>
		<p>
			{#if ask.phase === 'joining'}{ask.message ?? 'Получаем данные сайта…'}
			{:else if ask.phase === 'waiting'}На компьютере {target ? `«${target}»` : 'с сайтом'} нажмите
				<strong>«Разрешить»</strong>
			{:else}Связываемся с сайтом…{/if}
		</p>
	</div>
{:else}
	<div class="join">
		<div class="found">
			<p class="head">
				<Wifi size={16} />
				<span>{searching ? 'Ищем сайт в этой сети…' : 'В этой сети'}</span>
				{#if !searching}<Button size="s" variant="ghost" label="Искать снова" onclick={search}
						><RefreshCw size={14} /></Button
					>{/if}
			</p>
			{#if found?.length}
				<ul>
					{#each found as f (f.site + f.computer)}
						<li>
							<Laptop size={18} />
							<span class="grow">
								<strong>{f.name || 'Компьютер с сайтом'}</strong>
								<span class="faint small">{host(f.url)}</span>
							</span>
							<Button size="s" variant="primary" onclick={() => connect(f.url, f.name)}
								>Подключить</Button
							>
						</li>
					{/each}
				</ul>
			{:else if found && !searching}
				<p class="faint small">
					Не нашли — компьютер с сайтом в другой сети или выключен. Введите адрес сайта ниже.
				</p>
			{/if}
		</div>
		<form onsubmit={submit}>
			<label class="label" for="peer-url">Адрес сайта</label>
			<div class="line">
				<input
					id="peer-url"
					class="input"
					bind:value={url}
					placeholder="campus.cloudpub.ru"
					autocomplete="url"
					spellcheck="false"
				/>
				<Button type="submit" disabled={!url.trim()}>Подключить</Button>
			</div>
			<p class="hint">
				Тот же адрес, по которому заходит группа. Кода не нужно: на компьютере с сайтом спросят
				«Разрешить?».
			</p>
		</form>
		{#if error}<p class="error-text" role="alert">{error}</p>{/if}
	</div>
{/if}

<style>
	.join,
	form {
		display: flex;
		flex-direction: column;
		gap: var(--s3);
		text-align: left;
	}
	p {
		margin: 0;
	}
	.head {
		display: flex;
		align-items: center;
		gap: 8px;
		font-weight: 600;
	}
	ul {
		margin: var(--s2) 0 0;
		padding: 0;
		list-style: none;
		display: flex;
		flex-direction: column;
		gap: 6px;
	}
	li {
		display: flex;
		align-items: center;
		gap: 10px;
		padding: 8px 10px;
		border-radius: var(--r);
		background: var(--surface-2);
	}
	.grow {
		display: flex;
		flex-direction: column;
		flex: 1;
		min-width: 0;
	}
	.grow span {
		overflow-wrap: anywhere;
	}
	.line {
		display: flex;
		gap: 8px;
	}
	.line input {
		flex: 1;
		min-width: 0;
	}
	.state {
		display: flex;
		align-items: center;
		gap: var(--s3);
		padding: var(--s4);
		border-radius: var(--r);
		background: var(--surface-2);
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
	@keyframes spin {
		to {
			transform: rotate(360deg);
		}
	}
</style>
