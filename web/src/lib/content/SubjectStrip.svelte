<script lang="ts">
	import { tick } from 'svelte';
	import { ChevronLeft, ChevronRight } from '@lucide/svelte';
	import type { Subject } from '$lib/types';
	import SubjectGlyph from '$lib/ui/SubjectGlyph.svelte';

	// Карусель предметов на странице предмета (0.9.5): капсулы со значком и названием – видно, что
	// за предмет, без угадывания по картинке; текущий – в цвете предмета и прокручен в середину.
	// Что не поместилось – за краем с затуханием, у края – стрелка: мышью без сенсорной панели полосу
	// иначе не прокрутить.
	let { subjects, current }: { subjects: Subject[]; current: number } = $props();

	let strip: HTMLElement | undefined = $state();
	let moreLeft = $state(false);
	let moreRight = $state(false);

	$effect(() => {
		void current;
		void subjects.length;
		tick().then(() => {
			strip
				?.querySelector('[aria-current="page"]')
				?.scrollIntoView({ inline: 'center', block: 'nearest' });
			edges();
		});
	});
	$effect(() => {
		if (!strip) return;
		const seen = new ResizeObserver(edges);
		seen.observe(strip);
		return () => seen.disconnect();
	});

	function edges() {
		if (!strip) return;
		moreLeft = strip.scrollLeft > 4;
		moreRight = strip.scrollLeft + strip.clientWidth < strip.scrollWidth - 4;
	}

	function scrollStrip(dir: -1 | 1) {
		strip?.scrollBy({ left: dir * Math.max(160, strip.clientWidth * 0.7), behavior: 'smooth' });
	}
</script>

<div class="strip-wrap" class:more-left={moreLeft} class:more-right={moreRight}>
	<nav class="strip" aria-label="Другие предметы" bind:this={strip} onscroll={edges}>
		{#each subjects as o (o.id)}
			<a
				href="/subjects/{o.id}"
				class="pill-s"
				class:on={o.id === current}
				style:--c={o.color}
				aria-current={o.id === current ? 'page' : undefined}
				title={o.name}
				data-sveltekit-replacestate
			>
				<SubjectGlyph id={o.id} name={o.name} color={o.color} icon={o.icon} size={30} />
				<span class="n">{o.name}</span>
			</a>
		{/each}
	</nav>
	{#if moreLeft}
		<button
			class="strip-arrow left"
			tabindex="-1"
			aria-label="Предыдущие предметы"
			onclick={() => scrollStrip(-1)}><ChevronLeft size={18} /></button
		>
	{/if}
	{#if moreRight}
		<button
			class="strip-arrow right"
			tabindex="-1"
			aria-label="Следующие предметы"
			onclick={() => scrollStrip(1)}><ChevronRight size={18} /></button
		>
	{/if}
</div>

<style>
	.strip-wrap {
		--fade: 56px;
		position: relative;
	}
	/* Полоса – в границах колонки (раньше выходила за неё на --s4, и крайние предметы уходили под
	   фон страницы); запас по краям – под увеличенный текущий предмет и тень. */
	.strip {
		display: flex;
		align-items: center;
		gap: 8px;
		padding: 4px 3px;
		scroll-padding-inline: 6px;
		overflow-x: auto;
		scrollbar-width: none;
		scroll-behavior: smooth;
	}
	/* Не всё поместилось – край затухает: видно, что полоса продолжается. */
	.more-right .strip {
		mask-image: linear-gradient(90deg, #000 calc(100% - var(--fade)), transparent);
	}
	.more-left .strip {
		mask-image: linear-gradient(90deg, transparent, #000 var(--fade));
	}
	.more-left.more-right .strip {
		mask-image: linear-gradient(
			90deg,
			transparent,
			#000 var(--fade),
			#000 calc(100% - var(--fade)),
			transparent
		);
	}
	/* Стрелки – для мыши; на телефоне полосу листают пальцем. */
	.strip-arrow {
		position: absolute;
		top: 50%;
		display: none;
		place-items: center;
		width: 36px;
		height: 36px;
		padding: 0;
		border: 1px solid var(--border);
		border-radius: 50%;
		background: var(--surface);
		color: var(--text);
		box-shadow: var(--shadow-2);
		translate: 0 -50%;
		transition:
			transform 140ms var(--ease),
			background-color var(--dur) var(--ease);
	}
	.strip-arrow:hover {
		background: var(--surface-2);
		transform: scale(1.06);
	}
	.strip-arrow.left {
		left: 2px;
	}
	.strip-arrow.right {
		right: 2px;
	}
	@media (hover: hover) and (pointer: fine) {
		.strip-arrow {
			display: grid;
		}
	}
	.strip::-webkit-scrollbar {
		display: none;
	}
	.pill-s {
		flex: none;
		display: inline-flex;
		align-items: center;
		gap: 8px;
		height: 44px;
		max-width: 230px;
		padding: 0 14px 0 6px;
		border: 1px solid var(--border);
		border-radius: var(--r-full);
		background: var(--surface);
		color: var(--text-2);
		font-size: 14px;
		font-weight: 600;
		text-decoration: none;
		transition:
			background-color var(--dur) var(--ease),
			border-color var(--dur) var(--ease),
			color var(--dur) var(--ease),
			transform 140ms var(--ease);
	}
	.pill-s:hover {
		color: var(--text);
		border-color: var(--border-strong);
		text-decoration: none;
	}
	.pill-s:active {
		transform: scale(0.97);
	}
	.pill-s :global(.glyph) {
		border-radius: 50%;
	}
	.pill-s.on {
		border-color: var(--c);
		background: color-mix(in srgb, var(--c) 16%, var(--surface));
		color: var(--text);
		box-shadow: 0 0 0 1px var(--c);
	}
	.pill-s.on :global(.glyph) {
		background: var(--c);
		color: #fff;
	}
	.n {
		min-width: 0;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	@media (min-width: 900px) {
		.strip-wrap {
			margin: 0;
		}
		.strip {
			padding-left: 2px;
			padding-right: 2px;
		}
	}
</style>
