<script lang="ts">
	import { X } from '@lucide/svelte';
	import QrCode from './QrCode.svelte';

	// QR на весь экран: показать на проекторе или экране телефона, чтобы все отсканировали.
	let {
		open = $bindable(false),
		value,
		title,
		hint = 'Наведите камеру телефона на код'
	}: { open?: boolean; value: string; title: string; hint?: string } = $props();

	let dialog: HTMLDialogElement | undefined = $state();

	$effect(() => {
		if (!dialog) return;
		if (open && !dialog.open) dialog.showModal();
		if (!open && dialog.open) dialog.close();
	});
</script>

<dialog bind:this={dialog} class="screen" onclose={() => (open = false)} aria-label={title}>
	{#if open}
		<button class="close" onclick={() => (open = false)} aria-label="Закрыть"
			><X size={24} /></button
		>
		<div class="inner">
			<h1>{title}</h1>
			<p class="hint">{hint}</p>
			<div class="code"><QrCode {value} label={title} /></div>
			<p class="url">{value}</p>
		</div>
	{/if}
</dialog>

<style>
	.screen {
		width: 100vw;
		max-width: 100vw;
		height: 100dvh;
		max-height: 100dvh;
		margin: 0;
		padding: 0;
		border: 0;
		background: #fff;
		color: #0d0d0f;
	}
	.screen::backdrop {
		background: #fff;
	}
	.inner {
		height: 100%;
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		gap: 16px;
		padding: 24px;
		text-align: center;
	}
	h1 {
		font-size: clamp(26px, 5vw, 44px);
	}
	.hint {
		font-size: clamp(16px, 2.4vw, 22px);
		color: #5e626b;
	}
	.code {
		width: min(78vw, 62dvh);
	}
	.url {
		max-width: 90vw;
		font: 500 13px var(--mono);
		color: #5e626b;
		word-break: break-all;
	}
	.close {
		position: absolute;
		top: 16px;
		right: 16px;
		display: grid;
		place-items: center;
		width: 48px;
		height: 48px;
		border: 0;
		border-radius: 50%;
		background: #f1f2f4;
		color: #0d0d0f;
	}
</style>
