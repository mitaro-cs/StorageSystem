<script lang="ts">
	import { page } from '$app/state';
	import { ChevronLeft } from '@lucide/svelte';

	/** Свайп от левого края назад — для телефонов и PWA без системного жеста. */
	let start: { x: number; y: number } | null = null;
	let dx = $state(0);

	function ontouchstart(e: TouchEvent) {
		const t = e.touches[0];
		if (t.clientX > 24 || page.url.pathname === '/' || history.length < 2) return;
		start = { x: t.clientX, y: t.clientY };
	}
	function ontouchmove(e: TouchEvent) {
		if (!start) return;
		const t = e.touches[0];
		if (Math.abs(t.clientY - start.y) > 60) {
			start = null;
			dx = 0;
			return;
		}
		dx = Math.max(0, Math.min(120, t.clientX - start.x));
	}
	function ontouchend() {
		if (start && dx > 80) history.back();
		start = null;
		dx = 0;
	}
</script>

<svelte:window {ontouchstart} {ontouchmove} {ontouchend} ontouchcancel={ontouchend} />

{#if dx > 0}
	<div
		class="edge"
		style:transform="translateX({dx - 48}px)"
		style:opacity={Math.min(1, dx / 80)}
		aria-hidden="true"
	>
		<ChevronLeft size={22} />
	</div>
{/if}

<style>
	.edge {
		position: fixed;
		z-index: 60;
		top: 45%;
		left: 0;
		display: grid;
		place-items: center;
		width: 44px;
		height: 44px;
		border-radius: 50%;
		background: var(--surface);
		box-shadow: var(--shadow-2);
		color: var(--accent);
		pointer-events: none;
	}
</style>
