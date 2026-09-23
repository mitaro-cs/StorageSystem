<script lang="ts">
	import type { Snippet } from 'svelte';

	interface Props {
		variant?: 'primary' | 'secondary' | 'ghost' | 'danger';
		size?: 's' | 'm';
		type?: 'button' | 'submit';
		form?: string;
		href?: string;
		/** Для ссылок наружу: _blank — в новой вкладке (в приложении хоста — в браузере). */
		target?: '_blank';
		loading?: boolean;
		disabled?: boolean;
		icon?: boolean;
		label?: string;
		onclick?: (e: MouseEvent) => void;
		children: Snippet;
	}

	let {
		variant = 'secondary',
		size = 'm',
		type = 'button',
		form,
		href,
		target,
		loading = false,
		disabled = false,
		icon = false,
		label,
		onclick,
		children
	}: Props = $props();
</script>

{#if href}
	<a
		{href}
		{target}
		rel={target ? 'noreferrer' : undefined}
		class="btn {variant} {size}"
		class:icon
		aria-label={label}
		title={label}
	>
		{@render children()}
	</a>
{:else}
	<button
		{type}
		{form}
		class="btn {variant} {size}"
		class:icon
		class:loading
		disabled={disabled || loading}
		aria-busy={loading}
		aria-label={label}
		title={label}
		{onclick}
	>
		<span class="content">{@render children()}</span>
		{#if loading}<span class="spinner" aria-hidden="true"></span>{/if}
	</button>
{/if}

<style>
	.btn {
		position: relative;
		display: inline-flex;
		align-items: center;
		justify-content: center;
		gap: 8px;
		height: 44px;
		padding: 0 20px;
		border: 1px solid transparent;
		border-radius: var(--r-full);
		font-weight: 580;
		font-size: 15px;
		white-space: nowrap;
		text-decoration: none;
		transition:
			background-color var(--dur) var(--ease),
			border-color var(--dur) var(--ease),
			transform 120ms var(--ease),
			box-shadow var(--dur) var(--ease);
		-webkit-tap-highlight-color: transparent;
	}
	.btn:active:not(:disabled) {
		transform: scale(0.97);
	}
	.btn:hover {
		text-decoration: none;
	}
	.btn:disabled {
		opacity: 0.5;
		cursor: not-allowed;
	}
	.s {
		height: 36px;
		padding: 0 14px;
		font-size: 14px;
	}
	.icon {
		width: 44px;
		padding: 0;
	}
	.icon.s {
		width: 36px;
	}
	.content {
		display: inline-flex;
		align-items: center;
		gap: 8px;
	}
	.loading .content {
		visibility: hidden;
	}
	.primary {
		background: var(--accent);
		color: var(--accent-text);
	}
	.primary:hover:not(:disabled) {
		background: var(--accent-hover);
	}
	.secondary {
		background: var(--surface);
		border-color: var(--border-strong);
		color: var(--text);
	}
	.secondary:hover:not(:disabled) {
		background: var(--surface-2);
	}
	.ghost {
		background: transparent;
		color: var(--text-2);
	}
	.ghost:hover:not(:disabled) {
		background: var(--surface-2);
		color: var(--text);
	}
	.danger {
		background: var(--danger-soft);
		color: var(--danger);
	}
	.danger:hover:not(:disabled) {
		filter: brightness(0.97);
	}
	.spinner {
		position: absolute;
		width: 16px;
		height: 16px;
		border: 2px solid currentColor;
		border-right-color: transparent;
		border-radius: 50%;
		animation: spin 0.7s linear infinite;
	}
	@keyframes spin {
		to {
			transform: rotate(360deg);
		}
	}
</style>
