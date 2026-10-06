<script lang="ts">
	import { Lightbulb, X } from '@lucide/svelte';
	import { closeTip } from '$lib/onboarding.svelte';
	import { slide } from '$lib/motion';

	// Подсказка при первом заходе в раздел (0.6): одна строка, крестик – и больше не появится
	// (на всех устройствах). Показывать – по tipOpen(id), код грузится лениво.
	let { id, text }: { id: string; text: string } = $props();
	let open = $state(true);

	function close() {
		open = false;
		closeTip(id);
	}
</script>

{#if open}
	<div class="tip" role="note" out:slide>
		<span class="icon"><Lightbulb size={17} /></span>
		<p>{text}</p>
		<button onclick={close} aria-label="Понятно, скрыть подсказку"><X size={16} /></button>
	</div>
{/if}

<style>
	.tip {
		display: flex;
		align-items: center;
		gap: var(--s3);
		margin-bottom: var(--s4);
		padding: 10px 8px 10px var(--s3);
		border-radius: var(--r);
		background: var(--accent-soft);
		color: var(--text);
		animation: tip-in 300ms var(--ease);
	}
	.icon {
		display: grid;
		flex: none;
		color: var(--accent);
	}
	p {
		flex: 1;
		margin: 0;
		font-size: 14px;
		line-height: 1.4;
	}
	button {
		display: grid;
		flex: none;
		place-items: center;
		width: 32px;
		height: 32px;
		border: 0;
		border-radius: 50%;
		background: none;
		color: var(--text-2);
		cursor: pointer;
	}
	button:hover {
		background: color-mix(in srgb, var(--text) 8%, transparent);
	}
	@keyframes tip-in {
		from {
			opacity: 0;
			transform: translateY(-4px);
		}
	}
	@media (prefers-reduced-motion: reduce) {
		.tip {
			animation: none;
		}
	}
</style>
