<script lang="ts">
	import { onMount } from 'svelte';
	import {
		BellRing,
		CircleCheck,
		Compass,
		Download,
		EllipsisVertical,
		MonitorDown,
		Plus,
		Share,
		SquarePlus
	} from '@lucide/svelte';
	import { install, installed, pwa } from '$lib/pwa.svelte';
	import Button from '$lib/ui/Button.svelte';
	import BackBar from '$lib/ui/BackBar.svelte';

	type Platform = 'ios' | 'android' | 'desktop';
	const TABS: { value: Platform; label: string }[] = [
		{ value: 'ios', label: 'iPhone' },
		{ value: 'android', label: 'Android' },
		{ value: 'desktop', label: 'Компьютер' }
	];

	let platform = $state<Platform>('android');
	let done = $state(false);

	onMount(() => {
		const ua = navigator.userAgent;
		platform = /iPhone|iPad|iPod/.test(ua) ? 'ios' : /Android/.test(ua) ? 'android' : 'desktop';
		done = installed();
	});

	async function installNow() {
		await install();
		done = installed();
	}

	// Шаги простыми словами; иконка — то, что человек увидит на экране.
	const STEPS: Record<Platform, { icon: typeof Share; title: string; text: string }[]> = {
		ios: [
			{
				icon: Compass,
				title: 'Откройте сайт в Safari',
				text: 'Если читаете это в Telegram или другом приложении — нажмите «Открыть в Safari».'
			},
			{
				icon: Share,
				title: 'Нажмите «Поделиться»',
				text: 'Квадрат со стрелкой вверх — внизу экрана (на iPad — вверху).'
			},
			{
				icon: SquarePlus,
				title: 'Выберите «На экран „Домой“»',
				text: 'Если пункта не видно, пролистайте список вниз.'
			},
			{
				icon: Plus,
				title: 'Нажмите «Добавить»',
				text: 'На экране появится иконка groupbase — дальше открывайте приложение с неё.'
			}
		],
		android: [
			{
				icon: Compass,
				title: 'Откройте сайт в Chrome или Яндекс Браузере',
				text: 'Если читаете это в Telegram — нажмите ⋮ и «Открыть в браузере».'
			},
			{
				icon: EllipsisVertical,
				title: 'Нажмите ⋮ в правом верхнем углу',
				text: 'В Яндекс Браузере — ⋮ рядом с адресной строкой.'
			},
			{
				icon: Download,
				title: '«Установить приложение»',
				text: 'Или «Добавить на главный экран» — название зависит от браузера.'
			},
			{
				icon: Plus,
				title: 'Подтвердите',
				text: 'Иконка groupbase появится на главном экране.'
			}
		],
		desktop: [
			{
				icon: Compass,
				title: 'Откройте сайт в Chrome, Edge или Яндекс Браузере',
				text: 'В Safari на Mac: меню «Файл» → «Добавить в Dock».'
			},
			{
				icon: MonitorDown,
				title: 'Нажмите значок установки в адресной строке',
				text: 'Экран со стрелкой справа от адреса. Или меню ⋮ → «Установить groupbase».'
			},
			{
				icon: Plus,
				title: 'Подтвердите «Установить»',
				text: 'groupbase откроется отдельным окном и появится в меню «Пуск» или в Dock.'
			}
		]
	};
</script>

<svelte:head><title>Установка на телефон · groupbase</title></svelte:head>

<BackBar href="/" label="Установить приложение" />

{#if done}
	<div class="card ok">
		<CircleCheck size={40} />
		<h1>Приложение установлено</h1>
		<p class="muted">Открывайте groupbase с иконки на экране — так быстрее и работает без сети.</p>
		<a class="next" href="/profile#notifications"
			><BellRing size={18} /> Теперь включите уведомления</a
		>
	</div>
{:else}
	<p class="lead">
		groupbase можно поставить на телефон как обычное приложение: иконка на экране, открывается без
		браузера, задания видны даже без интернета. Из магазина ничего качать не нужно.
	</p>

	{#if pwa.canInstall}
		<div class="one-tap">
			<Button variant="primary" onclick={installNow}><Download size={18} /> Установить</Button>
			<span class="muted small">Браузер умеет ставить в одно нажатие</span>
		</div>
	{/if}

	<div class="tabs" role="group" aria-label="Устройство">
		{#each TABS as t (t.value)}
			<button class:on={platform === t.value} onclick={() => (platform = t.value)}>{t.label}</button
			>
		{/each}
	</div>

	<ol class="timeline">
		{#each STEPS[platform] as s, i (s.title)}
			{@const Icon = s.icon}
			<li>
				<span class="node num" aria-hidden="true">{i + 1}</span>
				<div class="step card">
					<span class="icon"><Icon size={22} /></span>
					<span>
						<strong>{s.title}</strong>
						<span class="muted small">{s.text}</span>
					</span>
				</div>
			</li>
		{/each}
	</ol>

	<p class="faint small after">
		После установки откройте приложение с иконки и включите уведомления в профиле — на iPhone они
		работают только так.
	</p>
{/if}

<style>
	.lead {
		font-size: 16px;
		color: var(--text-2);
		margin-bottom: var(--s5);
	}
	.one-tap {
		display: flex;
		align-items: center;
		gap: var(--s3);
		flex-wrap: wrap;
		margin-bottom: var(--s5);
	}
	.tabs {
		display: flex;
		gap: 8px;
		margin-bottom: var(--s5);
	}
	.tabs button {
		height: 40px;
		padding: 0 18px;
		border: 1px solid var(--border);
		border-radius: var(--r-full);
		background: var(--surface);
		color: var(--text-2);
		font-weight: 550;
	}
	.tabs button.on {
		background: var(--accent);
		border-color: var(--accent);
		color: var(--accent-text);
	}
	.timeline {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: var(--s3);
	}
	.timeline li {
		position: relative;
		display: grid;
		grid-template-columns: 44px 1fr;
		gap: var(--s3);
		align-items: center;
	}
	.timeline li:not(:last-child)::before {
		content: '';
		position: absolute;
		left: 21px;
		top: calc(50% + 26px);
		height: calc(50% - 14px);
		width: 2px;
		background: var(--border);
	}
	.node {
		display: grid;
		place-items: center;
		width: 44px;
		height: 44px;
		border-radius: 50%;
		background: var(--accent);
		color: var(--accent-text);
		font-weight: 700;
		font-size: 17px;
	}
	.step {
		display: flex;
		align-items: center;
		gap: 14px;
		padding: 16px;
	}
	.step > span:last-child {
		display: flex;
		flex-direction: column;
		gap: 2px;
	}
	.icon {
		flex: none;
		display: grid;
		place-items: center;
		width: 48px;
		height: 48px;
		border-radius: 14px;
		background: var(--surface-2);
	}
	.after {
		margin-top: var(--s5);
	}
	.ok {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 10px;
		padding: var(--s6) var(--s5);
		text-align: center;
		color: var(--ok);
	}
	.ok h1 {
		color: var(--text);
		font-size: 24px;
	}
	.next {
		display: inline-flex;
		align-items: center;
		gap: 8px;
		height: 44px;
		margin-top: var(--s2);
		padding: 0 20px;
		border-radius: var(--r-full);
		background: var(--accent);
		color: var(--accent-text);
		font-weight: 580;
	}
	.next:hover {
		text-decoration: none;
	}
</style>
