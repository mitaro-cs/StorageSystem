<script lang="ts">
	import { onMount, tick } from 'svelte';
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { ArrowLeft, ArrowRight, X } from '@lucide/svelte';
	import { currentGroup, groups, groupsWith, hasSettings, session } from '$lib/session.svelte';
	import { finishWelcome } from '$lib/onboarding.svelte';
	import Button from '$lib/ui/Button.svelte';
	import { findTarget, placeCard, tourSteps } from './steps';
	import TourDemo from './TourDemo.svelte';

	// Тур для новичка (0.6): приветствие с Атлантом, потом подсказки на настоящих кнопках –
	// затемнено всё, кроме нужной, подсветка перетекает от кнопки к кнопке. В каждой подсказке –
	// мини-«запись» (TourDemo, 0.7). В конце – большой экран «Добро пожаловать»: логотип на
	// орбитах с искрами и залп конфетти (0.9.4: без сияния и градиентов, просьба владельца).
	// Esc, «Пропустить» или крестик – тур закончен и больше сам не покажется (и на других устройствах).

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
		// Подсказки – про «Сегодня»: тур идёт оттуда.
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
		// Масштаб интерфейса на компьютере – CSS zoom на <html> (lib/uiScale.ts): размеры от
		// getBoundingClientRect уже умножены на него, а top/left подсветки умножатся ещё раз – делим.
		const z = Number(document.documentElement.style.zoom) || 1;
		if (el) {
			const r = el.getBoundingClientRect();
			hole = {
				top: r.top / z - pad,
				left: r.left / z - pad,
				width: r.width / z + pad * 2,
				height: r.height / z + pad * 2
			};
		} else hole = null;
		const c = card?.getBoundingClientRect();
		pos = placeCard(
			hole,
			{ width: (c?.width ?? 320) / z, height: (c?.height ?? 180) / z },
			{ width: innerWidth / z, height: innerHeight / z }
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
		// Подсказки прокручивали «Сегодня» – возвращаем к началу.
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

	// Листание пальцем: влево – дальше, вправо – назад.
	let startX = 0;
	function touchStart(e: TouchEvent) {
		startX = e.touches[0].clientX;
	}
	function touchEnd(e: TouchEvent) {
		if (phase !== 'tour') return;
		const dx = e.changedTouches[0].clientX - startX;
		if (Math.abs(dx) > 60) (dx < 0 ? next : back)();
	}

	// Искры вокруг логотипа на финале: угол, расстояние, задержка.
	const SPARKS = [
		{ a: 20, r: 128, d: 0 },
		{ a: 75, r: 150, d: 700 },
		{ a: 130, r: 122, d: 300 },
		{ a: 190, r: 156, d: 1100 },
		{ a: 240, r: 130, d: 500 },
		{ a: 300, r: 148, d: 900 },
		{ a: 345, r: 118, d: 1400 }
	];

	// Залп конфетти снизу из двух углов: цвета темы и её пятен света, у каждой – своя траектория.
	const confetti = Array.from({ length: 90 }, (_, i) => {
		const left = i % 2 === 0;
		return {
			x: left ? Math.round(Math.random() * 12) : 88 + Math.round(Math.random() * 12),
			dx: Math.round((left ? 1 : -1) * (120 + Math.random() * 420)),
			h: Math.round(380 + Math.random() * 420),
			d: Math.round(Math.random() * 500),
			r: Math.round(Math.random() * 1080 - 540),
			c: ['#4f7df5', '#1fa37a', '#e0633a', '#d9a21b', '#a35cf0', 'var(--accent)'][i % 6]
		};
	});
</script>

{#snippet logo(cls: string)}
	<!-- Логотип из static/logo.svg: плитка выпрыгивает, Атлант поднимается, глобус падает ему
		     на руки и пружинит, потом медленно вращается. -->
	<svg class={cls} viewBox="-12 -12 56 56" aria-hidden="true">
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
{/snippet}

<div
	class="tour"
	role="dialog"
	aria-modal="true"
	aria-label="Знакомство с Campus"
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
			{@render logo('logo')}
			<h2>Привет{name ? `, ${name}` : ''}!</h2>
			<p class="muted">
				Это сайт {group ? `группы ${group}` : 'вашей группы'}. Покажу главное – полминуты.
			</p>
			<div class="actions">
				<Button variant="primary" onclick={start}>Поехали <ArrowRight size={17} /></Button>
				<Button variant="ghost" onclick={close}>Сам решу</Button>
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
					<TourDemo kind={s.id} />
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
		<div class="finale" role="document">
			<div class="confetti" aria-hidden="true">
				{#each confetti as p, i (i)}
					<i
						style:left="{p.x}%"
						style:--d="{p.d}ms"
						style:--r="{p.r}deg"
						style:--h="{p.h}px"
						style:--dx="{p.dx}px"
						style:background={p.c}
					></i>
				{/each}
			</div>
			<!-- Логотип на «орбитах»: два пунктирных эллипса со спутниками цветов предметов и
			     искры вокруг – без сияния и градиентов. -->
			<div class="stage">
				<div class="orbit o1" aria-hidden="true"><i></i></div>
				<div class="orbit o2" aria-hidden="true"><i></i><i></i></div>
				<div class="sparks" aria-hidden="true">
					{#each SPARKS as sp, i (i)}<b
							style:--a="{sp.a}deg"
							style:--rr="{sp.r}px"
							style:--d="{sp.d}ms"
						></b>{/each}
				</div>
				{@render logo('logo big')}
			</div>
			<h2 aria-label="Добро пожаловать!">
				{#each [...'Добро пожаловать!'] as ch, i (i)}<span aria-hidden="true" style:--i={i}
						>{ch === ' ' ? '\u00a0' : ch}</span
					>{/each}
			</h2>
			<p class="lead">
				{name ? `${name}, теперь` : 'Теперь'} вы в {group ? `группе ${group}` : 'своей группе'}.
				Задания, пары и файлы – всё здесь.
			</p>
			<div class="actions">
				<Button variant="primary" onclick={close}>К заданиям <ArrowRight size={17} /></Button>
			</div>
			<p class="again faint small">
				Тур можно пройти снова: «Профиль» → «Приложение» → «Пройти тур».
			</p>
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
	/* Плитка как у логотипа: белая, глобус – в основном цвете (тёмный в любой теме – сетка белая). */
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
	/* Финал: на весь экран – логотип на орбитах с искрами, буквы по одной, залп конфетти. */
	.finale {
		position: fixed;
		inset: 0;
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		gap: var(--s3);
		padding: var(--s5);
		text-align: center;
		overflow: hidden;
		background: var(--bg);
		animation: fade-in 400ms var(--ease);
	}
	.stage {
		position: relative;
		display: grid;
		grid-template: 190px / 190px;
		place-items: center;
	}
	.stage > * {
		grid-area: 1 / 1;
	}
	/* Орбиты: эллипс наклонён, спутник едет по краю вместе с вращением. */
	.orbit {
		width: 300px;
		height: 300px;
		border: 1.5px dashed color-mix(in srgb, var(--text) 34%, transparent);
		border-radius: 50%;
		transform: rotateX(68deg);
		animation:
			orbit-in 700ms var(--ease) 900ms both,
			spin1 9s linear 900ms infinite;
	}
	.o2 {
		width: 380px;
		height: 380px;
		border-color: color-mix(in srgb, var(--text) 22%, transparent);
		transform: rotateX(72deg) rotateY(-24deg);
		animation:
			orbit-in 700ms var(--ease) 1.1s both,
			spin2 14s linear 1.1s infinite;
	}
	.orbit i {
		position: absolute;
		top: -6px;
		left: calc(50% - 6px);
		width: 12px;
		height: 12px;
		border-radius: 50%;
		background: var(--accent);
		box-shadow: 0 0 0 4px color-mix(in srgb, var(--accent) 22%, transparent);
	}
	.o2 i {
		background: #1fa37a;
		box-shadow: 0 0 0 4px rgb(31 163 122 / 0.22);
	}
	.o2 i + i {
		top: auto;
		bottom: -5px;
		width: 10px;
		height: 10px;
		background: #e0633a;
		box-shadow: 0 0 0 4px rgb(224 99 58 / 0.22);
	}
	.sparks b {
		position: absolute;
		top: 50%;
		left: 50%;
		width: 14px;
		height: 14px;
		margin: -7px;
		background: var(--accent);
		clip-path: polygon(50% 0, 62% 38%, 100% 50%, 62% 62%, 50% 100%, 38% 62%, 0 50%, 38% 38%);
		transform: rotate(var(--a)) translateX(var(--rr)) scale(0);
		animation: twinkle 2.2s var(--ease) calc(1.2s + var(--d)) infinite;
	}
	.sparks b:nth-child(3n) {
		background: #d9a21b;
	}
	.sparks b:nth-child(3n + 1) {
		background: #a35cf0;
	}
	.logo.big {
		position: relative;
		width: 190px;
		height: 190px;
	}
	.finale h2 {
		position: relative;
		margin: var(--s2) 0 0;
		font-size: clamp(34px, 7vw, 64px);
		font-weight: 800;
		letter-spacing: -0.03em;
		line-height: 1.05;
	}
	.finale h2 span {
		display: inline-block;
		color: var(--text);
		animation: letter 700ms cubic-bezier(0.2, 0.9, 0.25, 1.4) calc(900ms + var(--i) * 45ms) both;
	}
	.lead {
		position: relative;
		max-width: 460px;
		margin: 0;
		font-size: 17px;
		color: var(--text-2);
		animation: fade-up 600ms var(--ease) 1.7s both;
	}
	.finale .actions {
		position: relative;
		margin-top: var(--s3);
		animation: fade-up 600ms var(--ease) 2s both;
	}
	.finale .actions :global(.btn) {
		padding-inline: 28px;
		font-size: 16px;
		height: 48px;
	}
	.again {
		position: relative;
		margin: 0;
		animation: fade-up 600ms var(--ease) 2.3s both;
	}
	.confetti i {
		position: absolute;
		bottom: -14px;
		width: 9px;
		height: 14px;
		border-radius: 2px;
		animation: burst 2.6s cubic-bezier(0.15, 0.7, 0.35, 1) calc(800ms + var(--d)) both;
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
	@keyframes burst {
		0% {
			transform: translate(0, 0) rotate(0);
			opacity: 1;
		}
		55% {
			transform: translate(calc(var(--dx) * 0.8), calc(var(--h) * -1)) rotate(var(--r));
			opacity: 1;
		}
		100% {
			transform: translate(var(--dx), calc(var(--h) * -0.35)) rotate(calc(var(--r) * 2));
			opacity: 0;
		}
	}
	@keyframes letter {
		from {
			transform: translateY(0.6em) scale(0.6) rotate(-8deg);
			opacity: 0;
			filter: blur(6px);
		}
	}
	@keyframes spin1 {
		to {
			transform: rotateX(68deg) rotateZ(360deg);
		}
	}
	@keyframes spin2 {
		to {
			transform: rotateX(72deg) rotateY(-24deg) rotateZ(-360deg);
		}
	}
	@keyframes orbit-in {
		from {
			opacity: 0;
			scale: 0.5;
		}
	}
	@keyframes twinkle {
		0%,
		100% {
			transform: rotate(var(--a)) translateX(var(--rr)) scale(0);
		}
		40% {
			transform: rotate(var(--a)) translateX(var(--rr)) scale(1) rotate(45deg);
		}
	}
	@keyframes fade-up {
		from {
			opacity: 0;
			transform: translateY(14px);
		}
	}
	@media (prefers-reduced-motion: reduce) {
		.dim,
		.hole,
		.hello,
		.coach,
		.body,
		.finale,
		.finale *,
		.logo * {
			animation: none !important;
			transition: none !important;
		}
		.confetti,
		.orbit,
		.sparks {
			display: none;
		}
	}
</style>
