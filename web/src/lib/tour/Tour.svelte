<script lang="ts">
	import { onMount, tick } from 'svelte';
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { ArrowLeft, ArrowRight, X } from '@lucide/svelte';
	import { currentGroup, groups, groupsWith, hasSettings, session } from '$lib/session.svelte';
	import { finishWelcome } from '$lib/onboarding.svelte';
	import Button from '$lib/ui/Button.svelte';
	import { findTarget, placeCard, tourSteps } from './steps';

	// Тур для новичка (0.6): приветствие с Атлантом, потом подсказки на настоящих кнопках —
	// затемнено всё, кроме нужной, подсветка перетекает от кнопки к кнопке. В конце — конфетти.
	// Esc, «Пропустить» или крестик — тур закончен и больше сам не покажется (и на других устройствах).

	const phone = typeof matchMedia !== 'undefined' && matchMedia('(max-width: 899px)').matches;
	const steps = tourSteps({
		manage: hasSettings(),
		moderate: groupsWith('moderate_content').length > 0,
		phone
	});
	const name = session.me?.user.displayName.split(' ')[1] ?? session.me?.user.displayName ?? '';
	const group = currentGroup()?.name ?? groups()[0]?.name ?? '';

	let phase = $state<'hello' | 'tour' | 'done'>('hello');
	let step = $state(0);
	let hole = $state<{ top: number; left: number; width: number; height: number } | null>(null);
	let pos = $state({ top: 0, left: 0 });
	let card: HTMLElement | undefined = $state();
	let root: HTMLElement | undefined = $state();
	const s = $derived(steps[step]);

	onMount(() => {
		root?.focus();
		const update = () => phase === 'tour' && place();
		addEventListener('resize', update);
		addEventListener('scroll', update, true);
		return () => {
			removeEventListener('resize', update);
			removeEventListener('scroll', update, true);
		};
	});

	async function start() {
		// Подсказки — про «Сегодня»: тур идёт оттуда.
		if (page.url.pathname !== '/') await goto('/');
		phase = 'tour';
		await show(0);
	}

	async function show(i: number) {
		step = i;
		await tick();
		const el = findTarget(steps[i].targets);
		if (el) {
			const r = el.getBoundingClientRect();
			if (r.top < 0 || r.bottom > innerHeight) {
				el.scrollIntoView({ block: 'center', behavior: 'instant' });
				await tick();
			}
		}
		place();
		card?.querySelector<HTMLElement>('.foot button:last-child')?.focus();
	}

	function place() {
		const el = findTarget(s.targets);
		const pad = 6;
		if (el) {
			const r = el.getBoundingClientRect();
			hole = {
				top: r.top - pad,
				left: r.left - pad,
				width: r.width + pad * 2,
				height: r.height + pad * 2
			};
		} else hole = null;
		const c = card?.getBoundingClientRect();
		pos = placeCard(
			hole,
			{ width: c?.width ?? 320, height: c?.height ?? 180 },
			{ width: innerWidth, height: innerHeight }
		);
	}

	function next() {
		if (step < steps.length - 1) show(step + 1);
		else phase = 'done';
	}

	function back() {
		if (step > 0) show(step - 1);
	}

	function close() {
		// Подсказки прокручивали «Сегодня» — возвращаем к началу.
		if (phase !== 'hello') scrollTo({ top: 0, behavior: 'smooth' });
		finishWelcome();
	}

	function keydown(e: KeyboardEvent) {
		if (e.key === 'Escape') {
			e.preventDefault();
			close();
		} else if (phase === 'tour' && e.key === 'ArrowRight') next();
		else if (phase === 'tour' && e.key === 'ArrowLeft') back();
	}

	// Листание пальцем: влево — дальше, вправо — назад.
	let startX = 0;
	function touchStart(e: TouchEvent) {
		startX = e.touches[0].clientX;
	}
	function touchEnd(e: TouchEvent) {
		if (phase !== 'tour') return;
		const dx = e.changedTouches[0].clientX - startX;
		if (Math.abs(dx) > 60) (dx < 0 ? next : back)();
	}

	// Конфетти: цвета темы и пятен «Ауры», у каждой — своя траектория.
	const confetti = Array.from({ length: 36 }, (_, i) => ({
		x: Math.round(Math.random() * 100),
		d: Math.round(Math.random() * 600),
		r: Math.round(Math.random() * 720 - 360),
		c: ['var(--accent)', 'var(--mesh-1)', 'var(--mesh-2)', 'var(--mesh-3)', 'var(--urgent)'][i % 5]
	}));
</script>

<div
	class="tour"
	role="dialog"
	aria-modal="true"
	aria-label="Знакомство с groupbase"
	tabindex="-1"
	bind:this={root}
	onkeydown={keydown}
	ontouchstart={touchStart}
	ontouchend={touchEnd}
>
	{#if phase === 'tour' && hole}
		<div
			class="hole"
			style:top="{hole.top}px"
			style:left="{hole.left}px"
			style:width="{hole.width}px"
			style:height="{hole.height}px"
		></div>
	{:else}
		<div class="dim"></div>
	{/if}

	{#if phase === 'hello'}
		<div class="hello" role="document">
			<!-- Логотип из static/logo.svg: плитка выпрыгивает, Атлант поднимается, глобус падает ему
			     на руки и пружинит, потом медленно вращается. -->
			<svg class="logo" viewBox="-12 -12 56 56" aria-hidden="true">
				<g class="tile">
					<rect class="bg" width="32" height="32" rx="9" />
					<g transform="scale(0.0434783)">
						<g class="globe">
							<circle class="ball" cx="347.8" cy="273.1" r="165" />
							<use href="/logo.svg#grid" />
						</g>
						<g class="fig">
							<use href="/logo.svg#figure" />
							<use href="/logo.svg#eye" />
						</g>
					</g>
				</g>
			</svg>
			<h2>Привет{name ? `, ${name}` : ''}!</h2>
			<p class="muted">
				Это сайт {group ? `группы ${group}` : 'вашей группы'}. Покажу главное — полминуты.
			</p>
			<div class="actions">
				<Button variant="primary" onclick={start}>Поехали <ArrowRight size={17} /></Button>
				<Button variant="ghost" onclick={close}>Сам разберусь</Button>
			</div>
		</div>
	{:else if phase === 'tour'}
		<div
			class="card coach"
			bind:this={card}
			style:top="{pos.top}px"
			style:left="{pos.left}px"
			aria-live="polite"
		>
			<div class="progress" aria-label="Шаг {step + 1} из {steps.length}">
				{#each steps as st, i (st.id)}<i class:on={i <= step}></i>{/each}
			</div>
			<button class="x" onclick={close} aria-label="Пропустить знакомство"><X size={16} /></button>
			{#key step}
				<div class="body">
					<h3>{s.title}</h3>
					<p>{s.text}</p>
				</div>
			{/key}
			<div class="foot">
				<span class="faint small num">{step + 1} из {steps.length}</span>
				{#if step > 0}<Button variant="ghost" size="s" onclick={back} label="Назад"
						><ArrowLeft size={16} /></Button
					>{/if}
				<Button variant="primary" size="s" onclick={next}>
					{step === steps.length - 1 ? 'Готово' : 'Дальше'}
				</Button>
			</div>
		</div>
	{:else}
		<div class="hello done" role="document">
			<div class="confetti" aria-hidden="true">
				{#each confetti as p, i (i)}
					<i style:left="{p.x}%" style:--d="{p.d}ms" style:--r="{p.r}deg" style:background={p.c}
					></i>
				{/each}
			</div>
			<h2>Готово!</h2>
			<p class="muted">Тур можно пройти снова: «Профиль» → «Приложение» → «Как пользоваться».</p>
			<div class="actions">
				<Button variant="primary" onclick={close}>К заданиям</Button>
			</div>
		</div>
	{/if}
</div>

<style>
	.tour {
		position: fixed;
		inset: 0;
		z-index: 1000;
		outline: none;
	}
	.dim,
	.hole {
		position: fixed;
		pointer-events: none;
	}
	.dim {
		inset: 0;
		background: var(--overlay);
		animation: fade-in 250ms var(--ease);
	}
	/* Подсветка: прозрачная рамка, всё вокруг затемнено её огромной тенью. */
	.hole {
		border-radius: 16px;
		box-shadow:
			0 0 0 3px var(--accent),
			0 0 0 9999px var(--overlay);
		transition:
			top 380ms cubic-bezier(0.3, 1.2, 0.5, 1),
			left 380ms cubic-bezier(0.3, 1.2, 0.5, 1),
			width 380ms cubic-bezier(0.3, 1.2, 0.5, 1),
			height 380ms cubic-bezier(0.3, 1.2, 0.5, 1);
		animation: pulse 1.8s ease-in-out infinite;
	}
	.hello {
		position: fixed;
		top: 50%;
		left: 50%;
		translate: -50% -50%;
		width: min(400px, calc(100vw - 32px));
		padding: var(--s6) var(--s5) var(--s5);
		border-radius: var(--r-xl);
		background: var(--surface);
		box-shadow: var(--shadow-3);
		text-align: center;
		animation: rise 450ms cubic-bezier(0.2, 0.9, 0.25, 1.15);
	}
	.hello h2 {
		margin: var(--s2) 0 var(--s2);
		font-size: 26px;
	}
	.hello p {
		margin: 0 0 var(--s5);
	}
	.actions {
		display: flex;
		flex-direction: column;
		gap: var(--s2);
	}
	.logo {
		display: block;
		margin: 0 auto;
		width: 132px;
		height: 132px;
		overflow: visible;
	}
	.tile {
		transform-box: fill-box;
		transform-origin: center;
		animation: pop 600ms cubic-bezier(0.2, 0.9, 0.25, 1.3) both;
	}
	/* Плитка как у логотипа: белая, глобус — в основном цвете (тёмный в любой теме — сетка белая). */
	.bg {
		fill: #fff;
		stroke: rgb(0 0 0 / 0.08);
		stroke-width: 0.3;
	}
	.ball {
		fill: var(--pal-accent, #0d0d0f);
	}
	.fig {
		transform-box: fill-box;
		animation: lift 600ms cubic-bezier(0.3, 1.3, 0.5, 1) 250ms both;
	}
	.globe {
		transform-box: fill-box;
		transform-origin: center;
		animation:
			drop 750ms cubic-bezier(0.3, 1.5, 0.5, 1) 550ms both,
			wobble 4s ease-in-out 1.4s infinite;
	}
	.coach {
		position: fixed;
		width: min(340px, calc(100vw - 24px));
		padding: var(--s4);
		box-shadow: var(--shadow-3);
		transition:
			top 380ms cubic-bezier(0.3, 1.2, 0.5, 1),
			left 380ms cubic-bezier(0.3, 1.2, 0.5, 1);
	}
	.progress {
		display: flex;
		gap: 4px;
		margin: 0 32px var(--s3) 0;
	}
	.progress i {
		flex: 1;
		height: 4px;
		border-radius: 2px;
		background: var(--surface-3);
		transition: background-color 300ms var(--ease);
	}
	.progress i.on {
		background: var(--accent);
	}
	.x {
		position: absolute;
		top: 8px;
		right: 8px;
		display: grid;
		place-items: center;
		width: 30px;
		height: 30px;
		border: 0;
		border-radius: 50%;
		background: none;
		color: var(--text-3);
		cursor: pointer;
	}
	.x:hover {
		background: var(--surface-2);
	}
	.body {
		animation: fade-in 250ms var(--ease);
	}
	.body h3 {
		margin: 0 0 4px;
		font-size: 17px;
	}
	.body p {
		margin: 0;
		color: var(--text-2);
		font-size: 14.5px;
	}
	.foot {
		display: flex;
		align-items: center;
		gap: var(--s2);
		margin-top: var(--s4);
	}
	.foot span {
		margin-right: auto;
	}
	.done {
		overflow: hidden;
	}
	.confetti i {
		position: absolute;
		top: -12px;
		width: 8px;
		height: 12px;
		border-radius: 2px;
		animation: fall 1.6s cubic-bezier(0.2, 0.7, 0.4, 1) var(--d) both;
	}
	@keyframes fade-in {
		from {
			opacity: 0;
		}
	}
	@keyframes rise {
		from {
			opacity: 0;
			translate: -50% -40%;
			scale: 0.96;
		}
	}
	@keyframes pop {
		from {
			transform: scale(0.4);
			opacity: 0;
		}
	}
	@keyframes lift {
		from {
			transform: translateY(120px);
			opacity: 0;
		}
	}
	@keyframes drop {
		from {
			transform: translateY(-420px);
			opacity: 0;
		}
		60% {
			opacity: 1;
		}
	}
	@keyframes wobble {
		0%,
		100% {
			transform: rotate(0);
		}
		30% {
			transform: rotate(-6deg);
		}
		70% {
			transform: rotate(5deg);
		}
	}
	@keyframes pulse {
		50% {
			box-shadow:
				0 0 0 6px color-mix(in srgb, var(--accent) 45%, transparent),
				0 0 0 9999px var(--overlay);
		}
	}
	@keyframes fall {
		from {
			transform: translateY(0) rotate(0);
			opacity: 1;
		}
		to {
			transform: translateY(420px) rotate(var(--r));
			opacity: 0;
		}
	}
	@media (prefers-reduced-motion: reduce) {
		.tour *,
		.hole {
			animation: none !important;
			transition: none !important;
		}
		.confetti {
			display: none;
		}
	}
</style>
