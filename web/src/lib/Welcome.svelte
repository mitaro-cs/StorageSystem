<script lang="ts">
	import { onMount, type Component } from 'svelte';
	import { goto } from '$app/navigation';
	import {
		BellRing,
		BookOpen,
		CalendarCheck,
		Flag,
		ShieldCheck,
		Smartphone,
		WifiOff,
		X
	} from '@lucide/svelte';
	import { fly } from '$lib/motion';
	import { appIcon, iconSrc } from '$lib/appIcon.svelte';
	import { canManage, currentGroup, groups } from '$lib/session.svelte';
	import { finishWelcome } from '$lib/onboarding.svelte';
	import { install, installed, pwa } from '$lib/pwa.svelte';
	import Button from '$lib/ui/Button.svelte';
	import { typo } from '$lib/typo';

	// Знакомство: что такое groupbase и как им пользоваться — несколько карточек с листанием.
	// Одна мысль на карточку и одна короткая фраза: длинное никто не читает.
	interface Step {
		icon?: Component<{ size?: number | string; strokeWidth?: number | string }>;
		tone: string;
		title: string;
		text: string;
		action?: { label: string; run: () => void };
	}

	const group = currentGroup()?.name ?? groups()[0]?.name ?? '';
	const staff = canManage();

	const steps: Step[] = [
		{
			tone: 'brand',
			title: 'Привет! Это groupbase',
			text: `Сайт ${group ? `группы ${group}` : 'вашей группы'}: задания, новости и файлы предметов — в одном месте.`
		},
		{
			icon: CalendarCheck,
			tone: 'blue',
			title: 'Главное — на «Сегодня»',
			text: 'Что сдать на неделе и свежие новости. Сделали задание — поставьте галочку.'
		},
		{
			icon: BookOpen,
			tone: 'green',
			title: 'Всё по предметам',
			text: 'У каждого предмета — задания, файлы и чат. Лекции открываются прямо здесь.'
		},
		{
			icon: WifiOff,
			tone: 'violet',
			title: 'Работает без интернета',
			text: 'Открытое сохраняется на устройстве — пригодится в метро и на паре.'
		},
		{
			icon: BellRing,
			tone: 'amber',
			title: 'Не пропустите срок',
			text: 'Уведомления о новых заданиях и дедлайнах придут, даже когда сайт закрыт.',
			action: {
				label: 'Включить уведомления',
				run: () => done('/profile?tab=notifications')
			}
		},
		{
			icon: Smartphone,
			tone: 'teal',
			title: 'Как обычное приложение',
			text: 'Добавьте сайт на экран «Домой» — он откроется без адресной строки.',
			action: installed()
				? undefined
				: pwa.canInstall
					? { label: 'Установить', run: () => install() }
					: { label: 'Как установить', run: () => done('/install') }
		},
		staff
			? {
					icon: ShieldCheck,
					tone: 'red',
					title: 'Вы помогаете группе',
					text: 'Публикуйте кнопкой «+». Людей зовите в «Управлении», жалобы ждут в «Модерации».'
				}
			: {
					icon: Flag,
					tone: 'red',
					title: 'Что-то не так?',
					text: 'Нажмите «Пожаловаться» в меню записи. Модераторы разберутся, а ваше имя не увидят.'
				}
	];

	let step = $state(0);
	let dir = $state(1);
	let dialog: HTMLDialogElement | undefined = $state();
	const s = $derived(steps[step]);
	const last = $derived(step === steps.length - 1);

	onMount(() => {
		dialog?.showModal();
	});

	function go(to: number) {
		if (to < 0 || to >= steps.length) return;
		dir = to > step ? 1 : -1;
		step = to;
	}

	function done(to?: string) {
		dialog?.close();
		finishWelcome();
		if (to) goto(to);
	}

	// Листание пальцем: влево — дальше, вправо — назад.
	let startX = 0;
	function touchStart(e: TouchEvent) {
		startX = e.touches[0].clientX;
	}
	function touchEnd(e: TouchEvent) {
		const dx = e.changedTouches[0].clientX - startX;
		if (Math.abs(dx) > 50) go(step + (dx < 0 ? 1 : -1));
	}

	function keydown(e: KeyboardEvent) {
		if (e.key === 'ArrowRight') go(step + 1);
		if (e.key === 'ArrowLeft') go(step - 1);
	}
</script>

<dialog
	bind:this={dialog}
	class="welcome"
	aria-label="Знакомство с groupbase"
	onclose={() => finishWelcome()}
	onkeydown={keydown}
	ontouchstart={touchStart}
	ontouchend={touchEnd}
>
	<div class="panel">
		<button class="x" onclick={() => done()} aria-label="Закрыть"><X size={18} /></button>
		{#key step}
			<section class="step" in:fly={{ x: dir * 36, duration: 340 }}>
				<div class="art {s.tone}">
					{#if s.icon}
						<s.icon size={40} strokeWidth={1.8} />
					{:else}
						<img src={iconSrc(appIcon.id)} alt="" width="96" height="96" />
					{/if}
				</div>
				<h2>{typo(s.title)}</h2>
				<p>{typo(s.text)}</p>
				{#if s.action}
					<Button size="s" onclick={s.action.run}>{s.action.label}</Button>
				{/if}
			</section>
		{/key}
		<footer>
			<div class="dots" role="tablist" aria-label="Шаги">
				{#each steps as st, i (i)}
					<button
						role="tab"
						aria-selected={i === step}
						aria-label="Шаг {i + 1}: {st.title}"
						class:on={i === step}
						onclick={() => go(i)}
					></button>
				{/each}
			</div>
			<div class="nav">
				{#if step > 0}<Button variant="ghost" onclick={() => go(step - 1)}>Назад</Button>{/if}
				<Button variant="primary" onclick={() => (last ? done() : go(step + 1))}
					>{last ? 'Начать' : 'Далее'}</Button
				>
			</div>
		</footer>
	</div>
</dialog>

<style>
	.welcome {
		width: min(480px, calc(100vw - 24px));
		padding: 0;
		border: 0;
		border-radius: var(--r-xl);
		background: var(--surface);
		color: var(--text);
		box-shadow: 0 30px 80px -20px rgb(0 0 0 / 0.5);
		overflow: hidden;
		animation: w-in 420ms cubic-bezier(0.2, 0.9, 0.3, 1.2);
	}
	.welcome::backdrop {
		background: rgb(8 9 12 / 0.55);
		-webkit-backdrop-filter: blur(6px);
		backdrop-filter: blur(6px);
		animation: w-fade 300ms ease;
	}
	@keyframes w-in {
		from {
			opacity: 0;
			transform: translateY(24px) scale(0.96);
		}
	}
	@keyframes w-fade {
		from {
			opacity: 0;
		}
	}
	.panel {
		position: relative;
		display: flex;
		flex-direction: column;
		padding: var(--s6) var(--s5) var(--s4);
	}
	.x {
		position: absolute;
		top: 12px;
		right: 12px;
		display: grid;
		place-items: center;
		width: 36px;
		height: 36px;
		border: 0;
		border-radius: 50%;
		background: var(--surface-2);
		color: var(--text-2);
	}
	.step {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 14px;
		min-height: 290px;
		text-align: center;
	}
	.art {
		display: grid;
		place-items: center;
		width: 96px;
		height: 96px;
		margin-bottom: 6px;
		border-radius: 30px;
		color: #fff;
		box-shadow: 0 16px 34px -14px var(--c, rgb(0 0 0 / 0.5));
		animation:
			art-pop 520ms cubic-bezier(0.3, 1.6, 0.5, 1) both,
			art-float 3.4s ease-in-out 600ms infinite;
	}
	.art img {
		border-radius: 26px;
	}
	.art.brand {
		--c: rgb(0 0 0 / 0.45);
		background: transparent;
	}
	.art.blue {
		--c: #2f80ff;
		background: linear-gradient(135deg, #5b9bff, #1f5fe0);
	}
	.art.green {
		--c: #1fa37a;
		background: linear-gradient(135deg, #3cc98f, #12805a);
	}
	.art.violet {
		--c: #7c4dff;
		background: linear-gradient(135deg, #a07bff, #6230e6);
	}
	.art.amber {
		--c: #f09a2b;
		background: linear-gradient(135deg, #ffbd59, #e8790c);
	}
	.art.teal {
		--c: #14b8a6;
		background: linear-gradient(135deg, #34d3c0, #0e8f82);
	}
	.art.red {
		--c: #ee4d7e;
		background: linear-gradient(135deg, #ff7a9e, #d62d62);
	}
	@keyframes art-pop {
		from {
			opacity: 0;
			transform: scale(0.6) rotate(-8deg);
		}
	}
	@keyframes art-float {
		50% {
			transform: translateY(-5px);
		}
	}
	h2 {
		margin: 0;
		font-size: 26px;
		letter-spacing: -0.025em;
		text-wrap: balance;
	}
	/* Крупно и контрастно: читается и на большом мониторе, и в тёмной теме. */
	p {
		margin: 0;
		max-width: 30ch;
		color: color-mix(in srgb, var(--text) 82%, transparent);
		font-size: 17px;
		line-height: 1.5;
		text-wrap: pretty;
	}
	footer {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: var(--s3);
		margin-top: var(--s4);
	}
	.dots {
		display: flex;
		gap: 6px;
	}
	.dots button {
		width: 8px;
		height: 8px;
		padding: 0;
		border: 0;
		border-radius: 4px;
		background: var(--surface-3);
		transition:
			width 280ms var(--ease),
			background-color 280ms var(--ease);
	}
	.dots button.on {
		width: 22px;
		background: var(--accent);
	}
	.nav {
		display: flex;
		gap: 8px;
	}
	@media (max-width: 480px) {
		.welcome {
			margin-bottom: max(12px, env(safe-area-inset-bottom));
		}
		.panel {
			padding: var(--s5) var(--s4) var(--s4);
		}
	}
	@media (prefers-reduced-motion: reduce) {
		.welcome,
		.art {
			animation: none;
		}
	}
</style>
