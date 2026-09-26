<script lang="ts">
	import type { Snippet } from 'svelte';
	import { X } from '@lucide/svelte';
	import { ask } from './ask.svelte';

	interface Props {
		open: boolean;
		title: string;
		wide?: boolean;
		/**
		 * В окне есть несохранённое (написанный текст): клик мимо окна, Esc и крестик сначала
		 * спрашивают, а не закрывают молча. Кнопка «Отмена» в самом окне закрывает сразу.
		 */
		dirty?: boolean;
		/** Что пропадёт, если закрыть, — текст вопроса. */
		dirtyText?: string;
		onclose?: () => void;
		children: Snippet;
		footer?: Snippet;
	}

	let {
		open = $bindable(),
		title,
		wide = false,
		dirty = false,
		dirtyText = 'Написанное не сохранится.',
		onclose,
		children,
		footer
	}: Props = $props();
	let dialog: HTMLDialogElement | undefined = $state();
	const titleId = $props.id();
	let shake = $state(false);
	// Нажали мимо окна, а не выделяли текст в поле и отпустили снаружи: тогда click тоже приходит
	// на сам dialog, и окно закрывалось посреди выделения.
	let downOutside = false;

	$effect(() => {
		if (!dialog) return;
		if (open && !dialog.open) dialog.showModal();
		if (!open && dialog.open) dialog.close();
	});

	function close() {
		open = false;
		onclose?.();
	}

	/** Закрыть по желанию человека (мимо окна, Esc, крестик): написанное — только с вопросом. */
	async function tryClose() {
		if (!dirty) return close();
		shake = true;
		setTimeout(() => (shake = false), 400);
		if (
			await ask(dirtyText, {
				title: 'Закрыть без сохранения?',
				ok: 'Закрыть',
				cancel: 'Продолжить',
				danger: true
			})
		)
			close();
	}
</script>

<dialog
	bind:this={dialog}
	class:wide
	class:shake
	aria-labelledby={titleId}
	oncancel={(e) => {
		// Esc: при несохранённом — спрашиваем, а окно остаётся.
		if (dirty) {
			e.preventDefault();
			tryClose();
		}
	}}
	onclose={() => {
		if (open) close();
	}}
	onpointerdown={(e) => (downOutside = e.target === dialog)}
	onclick={(e) => {
		if (e.target === dialog && downOutside) tryClose();
		downOutside = false;
	}}
>
	{#if open}
		<div class="panel">
			<header>
				<h2 id={titleId}>{title}</h2>
				<button class="x" onclick={tryClose} aria-label="Закрыть"><X size={18} /></button>
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
		animation: pop 340ms cubic-bezier(0.2, 0.9, 0.3, 1.2);
	}
	/* Мимо окна с написанным текстом — окно вздрагивает и остаётся. */
	dialog.shake {
		animation: shake 360ms var(--ease);
	}
	@keyframes shake {
		20%,
		60% {
			transform: translateX(-6px);
		}
		40%,
		80% {
			transform: translateX(6px);
		}
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
		/* Лист снизу на всю ширину — и для «широких» окон тоже (новое задание, «Добавить людей»). */
		dialog,
		dialog.wide {
			width: 100vw;
			max-width: 100vw;
			margin: auto 0 0;
			border-radius: 18px 18px 0 0;
			max-height: 92dvh;
		}
		dialog[open] {
			animation: sheet 380ms cubic-bezier(0.2, 0.9, 0.25, 1.04);
		}
		footer {
			padding-bottom: calc(12px + env(safe-area-inset-bottom));
		}
	}
	@keyframes pop {
		from {
			opacity: 0;
			transform: translateY(14px) scale(0.95);
		}
	}
	@keyframes sheet {
		from {
			transform: translateY(100%);
		}
	}
	@keyframes fade-in {
		from {
			opacity: 0;
		}
	}
</style>
