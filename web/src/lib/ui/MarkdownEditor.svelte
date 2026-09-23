<script lang="ts">
	import { post } from '$lib/api';
	import Prose from './Prose.svelte';

	interface Props {
		value: string;
		label?: string;
		placeholder?: string;
		rows?: number;
		id?: string;
	}

	let {
		value = $bindable(),
		label = 'Текст',
		placeholder = 'Поддерживается Markdown: **жирный**, *курсив*, списки, ссылки',
		rows = 6,
		id = 'md-' + Math.random().toString(36).slice(2, 8)
	}: Props = $props();

	let mode = $state<'write' | 'preview'>('write');
	let html = $state('');
	let loading = $state(false);

	async function preview() {
		mode = 'preview';
		loading = true;
		try {
			html = (await post<{ html: string }>('/api/markdown', { text: value })).html;
		} finally {
			loading = false;
		}
	}
</script>

<div class="md">
	<div class="head">
		<label class="label" for={id}>{label}</label>
		<div class="switch" role="group" aria-label="Режим">
			<button type="button" class:on={mode === 'write'} onclick={() => (mode = 'write')}
				>Текст</button
			>
			<button type="button" class:on={mode === 'preview'} onclick={preview}>Просмотр</button>
		</div>
	</div>
	{#if mode === 'write'}
		<textarea {id} class="textarea" bind:value {rows} {placeholder}></textarea>
	{:else}
		<div class="preview" aria-live="polite">
			{#if loading}<span class="faint">Готовим просмотр…</span>{:else if html}<Prose
					{html}
				/>{:else}<span class="faint">Пусто</span>{/if}
		</div>
	{/if}
</div>

<style>
	.head {
		display: flex;
		align-items: center;
		justify-content: space-between;
		margin-bottom: 6px;
	}
	.head .label {
		margin: 0;
	}
	.switch {
		display: flex;
		gap: 2px;
		padding: 2px;
		background: var(--surface-2);
		border-radius: 8px;
	}
	.switch button {
		height: 26px;
		padding: 0 10px;
		border: 0;
		border-radius: 6px;
		background: transparent;
		font-size: 12.5px;
		color: var(--text-2);
	}
	.switch .on {
		background: var(--surface);
		color: var(--text);
		box-shadow: var(--shadow-1);
	}
	.preview {
		min-height: 120px;
		padding: 10px 12px;
		border: 1px dashed var(--border-strong);
		border-radius: var(--r-s);
	}
</style>
