<script lang="ts">
	import { dismiss, toasts } from '$lib/toasts.svelte';
	import { fly, flip } from '$lib/motion';
	import { CircleCheck, CircleAlert, Info } from '@lucide/svelte';
</script>

<div class="toaster" role="status" aria-live="polite">
	{#each toasts as t (t.id)}
		<button
			class="toast {t.kind}"
			in:fly={{ y: 16 }}
			out:fly={{ y: 8, duration: 150 }}
			animate:flip
			onclick={() => dismiss(t.id)}
		>
			{#if t.kind === 'ok'}<CircleCheck size={18} />{:else if t.kind === 'error'}<CircleAlert
					size={18}
				/>{:else}<Info size={18} />{/if}
			<span>{t.text}</span>
		</button>
	{/each}
</div>

<style>
	.toaster {
		position: fixed;
		z-index: 100;
		left: 50%;
		bottom: calc(var(--bottom-nav) + 16px + env(safe-area-inset-bottom));
		transform: translateX(-50%);
		display: flex;
		flex-direction: column-reverse;
		align-items: center;
		gap: 8px;
		width: min(440px, calc(100vw - 32px));
		pointer-events: none;
	}
	@media (min-width: 900px) {
		.toaster {
			bottom: 24px;
		}
	}
	.toast {
		pointer-events: auto;
		display: flex;
		align-items: center;
		gap: 10px;
		padding: 12px 16px;
		border: 0;
		border-radius: var(--r);
		background: var(--text);
		color: var(--bg);
		box-shadow: var(--shadow-3);
		font-size: 14px;
		text-align: left;
		max-width: 100%;
	}
	.toast.error {
		background: var(--danger);
		color: #fff;
	}
</style>
