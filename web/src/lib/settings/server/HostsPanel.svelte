<script lang="ts">
	import { onMount } from 'svelte';
	import { Cloud, Laptop, Monitor, Pencil, RefreshCw } from '@lucide/svelte';
	import { request } from '$lib/api';
	import { fmtAgo } from '$lib/format';
	import { session } from '$lib/session.svelte';
	import { toast, toastError } from '$lib/toasts.svelte';
	import { ask, askText } from '$lib/ui/ask.svelte';
	import Button from '$lib/ui/Button.svelte';
	import type { Hosts } from './types';

	// Сайт на нескольких компьютерах хоста: дома — на ПК, в вузе — на ноутбуке. Данные между ними
	// передаёт облачный диск, который есть на обоих.
	let h = $state<Hosts | null>(null);
	let busy = $state('');
	const here = $derived(!!session.me?.hostWindow);

	async function load() {
		try {
			h = await request<Hosts>('/api/host');
		} catch {
			h = null;
		}
	}
	onMount(() => {
		load();
		const t = setInterval(load, 30_000);
		return () => clearInterval(t);
	});

	async function act(key: string, path: string, body: unknown, method: 'POST' | 'PUT' = 'POST') {
		busy = key;
		try {
			h = await request<Hosts>(path, { method, body });
			return true;
		} catch (e) {
			toastError(e);
			return false;
		} finally {
			busy = '';
		}
	}

	async function enable(cloud: string, label: string) {
		if (
			!(await ask(
				`Сайт будет сохранять свои данные в «${label}», в папку groupbase-site. Второй компьютер возьмёт их оттуда. В этой папке — все данные группы и ключи шифрования: облако должно быть только вашим.`,
				{ title: 'Работа на нескольких компьютерах', ok: 'Включить' }
			))
		)
			return;
		if (await act('enable', '/api/host/enable', { cloud }))
			toast('Готово: данные сохранены в облачную папку', 'ok');
	}

	async function disable() {
		if (
			!(await ask(
				'Сайт останется на этом компьютере, а другие компьютеры перестанут забирать его сами. Папка в облаке останется — её можно удалить вручную.',
				{ title: 'Выключить перенос', ok: 'Выключить', danger: true }
			))
		)
			return;
		await act('disable', '/api/host/disable', {});
	}

	async function rename() {
		const name = await askText('Так этот компьютер увидят на других: «ПК дома», «Ноутбук».', {
			title: 'Имя компьютера',
			value: h?.computer?.name ?? '',
			maxlength: 40,
			ok: 'Сохранить'
		});
		if (name) await act('rename', '/api/host/name', { name }, 'PUT');
	}

	async function saveNow() {
		if (await act('save', '/api/host/snapshot', {})) toast('Данные сохранены в облако', 'ok');
	}
</script>

{#if h?.available}
	<h2 class="head">Несколько компьютеров</h2>
	{#if !h.enabled}
		<section class="card intro">
			<div class="pair" aria-hidden="true">
				<span><Monitor size={22} /></span>
				<i></i>
				<span><Laptop size={22} /></span>
			</div>
			<div>
				<strong>Дома — на ПК, в вузе — на ноутбуке</strong>
				<p class="small muted">
					Сайт работает то на одном вашем компьютере, то на другом, с одними и теми же данными и
					адресом. Закрыли приложение на одном — другой при запуске возьмёт свежие данные сам.
					Передаёт их облачный диск, который есть на обоих: Яндекс Диск, OneDrive, Google Диск,
					Dropbox или iCloud.
				</p>
			</div>
		</section>
		{#if here}
			{#if h.choices.length}
				<h3 class="sub">Через какой облачный диск</h3>
				<div class="choices">
					{#each h.choices as c (c.path)}
						<button disabled={!!busy} onclick={() => enable(c.path, c.label)}>
							<span class="row"><Cloud size={18} /> <strong>{c.label}</strong></span>
							<span class="small faint"
								>Он должен быть и на втором компьютере, с тем же аккаунтом</span
							>
						</button>
					{/each}
				</div>
			{:else}
				<p class="tip amber small">
					<span
						>На этом компьютере не найден облачный диск. Установите Яндекс Диск (или OneDrive,
						Google Диск, Dropbox, iCloud) здесь и на втором компьютере, войдите в один аккаунт — и
						здесь появится выбор.</span
					>
				</p>
			{/if}
		{:else}
			<p class="faint small">Включается в приложении groupbase на компьютере, где работает сайт.</p>
		{/if}
	{:else}
		<section class="card state">
			<div class="now">
				<span class="dot" class:ok={h.role === 'host'}></span>
				<div class="grow">
					<strong
						>{h.role === 'host'
							? `Сайт работает здесь — на «${h.computer?.name ?? ''}»`
							: h.message || 'Сайт работает на другом компьютере'}</strong
					>
					<p class="small muted">
						{h.snapshotAt
							? `Копия для других компьютеров — ${fmtAgo(h.snapshotAt)}${h.cloud ? ` (${h.cloud})` : ''}.`
							: 'Копии для других компьютеров ещё не было.'}
						Обновляется сама после каждого изменения.
					</p>
					{#if h.error}<p class="small bad">{h.error}</p>{/if}
				</div>
				{#if here}
					<Button size="s" variant="ghost" label="Переименовать компьютер" onclick={rename}
						><Pencil size={15} /></Button
					>
				{/if}
			</div>
			{#if here && h.role === 'host'}
				<div class="row wrap">
					<Button loading={busy === 'save'} onclick={saveNow}
						><RefreshCw size={16} /> Сохранить сейчас</Button
					>
				</div>
			{/if}
		</section>

		<h3 class="sub">Как перенести сайт</h3>
		<ol class="steps small">
			<li>
				На втором компьютере установите groupbase и тот же облачный диск{h.cloud
					? ` (${h.cloud})`
					: ''} с тем же аккаунтом.
			</li>
			<li>
				При первом запуске там выберите «Уже есть сайт в облачной папке» — он возьмёт данные и адрес
				сайта отсюда.
			</li>
			<li>
				Дальше просто закрывайте приложение там, где уходите («Выйти» в меню значка), и открывайте
				там, где работаете, — сайт переедет сам. Если на другом компьютере приложение открыто, там
				есть кнопка «Перенести сюда».
			</li>
		</ol>
		<p class="tip amber small">
			<span
				>Выключаете компьютер, не закрыв groupbase, — сайт тоже переедет, но через несколько минут,
				и последние изменения за минуту до выключения могут не успеть попасть в облако.</span
			>
		</p>
		{#if here && h.role === 'host'}
			<div>
				<Button variant="ghost" loading={busy === 'disable'} onclick={disable}
					>Выключить перенос между компьютерами</Button
				>
			</div>
		{/if}
	{/if}
{/if}

<style>
	.head {
		margin: var(--s6) 0 var(--s3);
	}
	.intro {
		display: flex;
		align-items: center;
		gap: var(--s4);
	}
	.intro p {
		margin: 4px 0 0;
	}
	.pair {
		flex: none;
		display: flex;
		align-items: center;
		gap: 6px;
		color: var(--text-2);
	}
	.pair span {
		display: grid;
		place-items: center;
		width: 44px;
		height: 44px;
		border-radius: 14px;
		background: var(--surface-2);
	}
	.pair i {
		width: 18px;
		border-top: 2px dashed var(--border-strong);
	}
	.sub {
		margin: var(--s5) 0 var(--s3);
	}
	.choices {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
		gap: 8px;
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
	.choices button:hover:not(:disabled) {
		border-color: var(--border-strong);
	}
	.row {
		display: flex;
		align-items: center;
		gap: 6px;
	}
	.state {
		display: flex;
		flex-direction: column;
		gap: var(--s3);
	}
	.now {
		display: flex;
		align-items: flex-start;
		gap: 12px;
	}
	.now p {
		margin: 4px 0 0;
	}
	.grow {
		flex: 1;
		min-width: 0;
	}
	.dot {
		flex: none;
		width: 10px;
		height: 10px;
		margin-top: 7px;
		border-radius: 50%;
		background: var(--text-3);
	}
	.dot.ok {
		background: var(--ok);
		box-shadow: 0 0 0 4px var(--ok-soft);
	}
	.bad {
		color: var(--danger);
	}
	.steps {
		margin: 0 0 var(--s3);
		padding-left: 20px;
		color: var(--text-2);
		line-height: 1.5;
	}
	.steps li + li {
		margin-top: 6px;
	}
	@media (max-width: 520px) {
		.intro {
			flex-direction: column;
			align-items: flex-start;
		}
	}
</style>
