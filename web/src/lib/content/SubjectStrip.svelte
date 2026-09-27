<script lang="ts">
	import { tick } from 'svelte';
	import { ChevronLeft, ChevronRight } from '@lucide/svelte';
	import type { Subject } from '$lib/types';
	import SubjectArt from '$lib/ui/SubjectArt.svelte';

	// Полоса миниатюр предметов на странице предмета: текущий крупнее и прокручен в видимую область.
	// Что не поместилось — за краем с затуханием, у края — стрелка: мышью без сенсорной панели полосу
	// иначе не прокрутить («часть уходит и не видна»).
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
				class="thumb"
				class:on={o.id === current}
				aria-current={o.id === current ? 'page' : undefined}
				title={o.name}
				aria-label={o.name}
				data-sveltekit-replacestate
			>
				<SubjectArt
					id={o.id}
					name={o.name}
					color={o.color}
					avatar={o.avatar}
					icon={o.icon}
					class="fill"
				/>
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
		margin: 0 calc(-1 * var(--s4));
	}
	.strip {
		display: flex;
		align-items: center;
		gap: 10px;
		padding: 6px var(--s4);
		overflow-x: auto;
		scrollbar-width: none;
		scroll-behavior: smooth;
	}
	/* Не всё поместилось — край затухает: видно, что полоса продолжается. */
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
	/* Стрелки — для мыши; на телефоне полосу листают пальцем. */
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
	.thumb {
		position: relative;
		flex: none;
		width: 60px;
		height: 72px;
		border-radius: 16px;
		opacity: 0.75;
		transition:
			width 220ms var(--ease),
			height 220ms var(--ease),
			opacity var(--dur) var(--ease);
	}
	.thumb:hover {
		opacity: 1;
	}
	.thumb.on {
		width: 84px;
		height: 96px;
		opacity: 1;
		box-shadow:
			0 0 0 3px var(--bg),
			0 0 0 5px var(--text);
		border-radius: 18px;
	}
	.thumb :global(.fill) {
		position: absolute;
		inset: 0;
		border-radius: inherit;
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
