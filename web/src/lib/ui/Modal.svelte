<script lang="ts">
	import type { Snippet } from 'svelte';
	import { X } from '@lucide/svelte';

	interface Props {
		open: boolean;
		title: string;
		wide?: boolean;
		onclose?: () => void;
		children: Snippet;
		footer?: Snippet;
	}

	let { open = $bindable(), title, wide = false, onclose, children, footer }: Props = $props();
	let dialog: HTMLDialogElement | undefined = $state();

	$effect(() => {
		if (!dialog) return;
		if (open && !dialog.open) dialog.showModal();
		if (!open && dialog.open) dialog.close();
	});

	function close() {
		open = false;
		onclose?.();
	}
</script>

<dialog
	bind:this={dialog}
	class:wide
	aria-labelledby="modal-title"
	onclose={() => {
		if (open) close();
	}}
	onclick={(e) => {
		if (e.target === dialog) close();
	}}
>
	{#if open}
		<div class="panel">
			<header>
				<h2 id="modal-title">{title}</h2>
				<button class="x" onclick={close} aria-label="Закрыть"><X size={18} /></button>
			</header>
			<div class="body">{@render children()}</div>
			{#if footer}<footer>{@render footer()}</footer>{/if}
		</div>
	{/if}
</dialog>

<style>
	dialog {
		width: min(560px, calc(100vw - 32px));
		max-height: min(86dvh, 900px);
		padding: 0;
		border: 0;
		border-radius: var(--r-xl);
		background: var(--surface);
		color: var(--text);
		box-shadow: var(--shadow-3);
		overflow: hidden;
	}
	dialog.wide {
		width: min(760px, calc(100vw - 32px));
	}
	dialog[open] {
		animation: pop 200ms var(--ease);
	}
	dialog::backdrop {
		background: var(--overlay);
		backdrop-filter: blur(2px);
		animation: fade-in 200ms var(--ease);
	}
	.panel {
		display: flex;
		flex-direction: column;
		max-height: inherit;
	}
	header {
		display: flex;
		align-items: center;
		gap: var(--s2);
		padding: 16px 16px 8px 20px;
	}
	h2 {
		flex: 1;
		font-size: 18px;
	}
	.x {
		display: grid;
		place-items: center;
		width: 32px;
		height: 32px;
		border: 0;
		border-radius: 8px;
		background: transparent;
		color: var(--text-2);
	}
	.x:hover {
		background: var(--surface-2);
	}
	.body {
		padding: 8px 20px 20px;
		overflow-y: auto;
	}
	footer {
		display: flex;
		justify-content: flex-end;
		gap: var(--s2);
		padding: 12px 20px;
		border-top: 1px solid var(--border);
		background: var(--surface);
	}
	@media (max-width: 640px) {
		dialog {
			width: 100vw;
			max-width: 100vw;
			margin: auto 0 0;
			border-radius: 18px 18px 0 0;
			max-height: 92dvh;
		}
		dialog[open] {
			animation: sheet 240ms var(--ease);
		}
		footer {
			padding-bottom: calc(12px + env(safe-area-inset-bottom));
		}
	}
	@keyframes pop {
		from {
			opacity: 0;
			transform: translateY(8px) scale(0.98);
		}
	}
	@keyframes sheet {
		from {
			transform: translateY(40%);
			opacity: 0.4;
		}
	}
	@keyframes fade-in {
		from {
			opacity: 0;
		}
	}
</style>
