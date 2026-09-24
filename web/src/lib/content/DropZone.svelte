<script lang="ts">
	import { Upload, X } from '@lucide/svelte';
	import { uploadFile } from '$lib/upload';
	import { fmtSize } from '$lib/format';
	import { slide } from '$lib/motion';
	import type { FileInfo } from '$lib/types';

	interface Props {
		files: FileInfo[];
		/** Сколько файлов ещё загружается: форма не отправляется, пока они не дойдут. */
		uploading?: number;
		multiple?: boolean;
		max?: number;
		label?: string;
	}

	let {
		files = $bindable(),
		uploading = $bindable(0),
		multiple = true,
		max = 10,
		label = 'Файлы'
	}: Props = $props();

	interface Pending {
		key: number;
		name: string;
		progress: number;
		error?: string;
	}

	let pending = $state<Pending[]>([]);
	// Счётчик для формы: загрузки без ошибки, которые ещё идут.
	const busy = $derived(pending.filter((p) => !p.error).length);
	$effect(() => {
		if (uploading !== busy) uploading = busy;
	});
	let over = $state(false);
	let input: HTMLInputElement | undefined = $state();
	let seq = 0;

	async function add(list: FileList | null) {
		if (!list) return;
		for (const file of Array.from(list).slice(0, Math.max(0, max - files.length))) {
			const p: Pending = { key: ++seq, name: file.name, progress: 0 };
			pending.push(p);
			const item = pending[pending.length - 1];
			try {
				const info = await uploadFile(file, (f) => (item.progress = f));
				files = [...files, info];
				pending = pending.filter((x) => x.key !== p.key);
			} catch (e) {
				item.error = e instanceof Error ? e.message : 'Ошибка загрузки';
			}
			if (!multiple) break;
		}
	}
</script>

<div class="dz">
	<span class="label">{label}</span>
	<button
		type="button"
		class="zone"
		class:over
		onclick={() => input?.click()}
		ondragover={(e) => {
			e.preventDefault();
			over = true;
		}}
		ondragleave={() => (over = false)}
		ondrop={(e) => {
			e.preventDefault();
			over = false;
			add(e.dataTransfer?.files ?? null);
		}}
	>
		<Upload size={20} />
		<span><strong>Выберите файл</strong> или перетащите сюда</span>
		<span class="faint small">PDF, документы, презентации, картинки, архивы</span>
	</button>
	<input
		bind:this={input}
		type="file"
		{multiple}
		hidden
		onchange={(e) => add(e.currentTarget.files)}
	/>

	{#each files as f (f.id)}
		<div class="item" transition:slide>
			<span class="name">{f.name}</span>
			<span class="faint small num">{fmtSize(f.size)}</span>
			<button
				type="button"
				class="x"
				aria-label="Убрать {f.name}"
				onclick={() => (files = files.filter((x) => x.id !== f.id))}><X size={15} /></button
			>
		</div>
	{/each}
	{#each pending as p (p.key)}
		<div class="item" transition:slide>
			<span class="name">{p.name}</span>
			{#if p.error}
				<span class="error-text small">{p.error}</span>
				<button
					type="button"
					class="x"
					aria-label="Убрать"
					onclick={() => (pending = pending.filter((x) => x.key !== p.key))}><X size={15} /></button
				>
			{:else}
				<span
					class="bar"
					role="progressbar"
					aria-valuenow={Math.round(p.progress * 100)}
					aria-valuemin={0}
					aria-valuemax={100}><span style:width="{p.progress * 100}%"></span></span
				>
			{/if}
		</div>
	{/each}
</div>

<style>
	.dz {
		display: flex;
		flex-direction: column;
		gap: 6px;
	}
	.label {
		font-size: 13px;
		font-weight: 550;
		color: var(--text-2);
	}
	.zone {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 4px;
		padding: 20px;
		border: 1.5px dashed var(--border-strong);
		border-radius: var(--r);
		background: var(--surface);
		color: var(--text-2);
		transition:
			border-color var(--dur) var(--ease),
			background-color var(--dur) var(--ease);
	}
	.zone:hover,
	.zone.over {
		border-color: var(--accent);
		background: var(--accent-soft);
		color: var(--accent);
	}
	.item {
		display: flex;
		align-items: center;
		gap: 10px;
		padding: 8px 10px;
		border-radius: var(--r-s);
		background: var(--surface-2);
	}
	.name {
		flex: 1;
		min-width: 0;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
		font-size: 14px;
	}
	.x {
		display: grid;
		place-items: center;
		width: 26px;
		height: 26px;
		border: 0;
		border-radius: 7px;
		background: transparent;
		color: var(--text-3);
	}
	.x:hover {
		background: var(--surface-3);
		color: var(--text);
	}
	.bar {
		width: 120px;
		height: 6px;
		border-radius: 3px;
		background: var(--surface-3);
		overflow: hidden;
	}
	.bar span {
		display: block;
		height: 100%;
		background: var(--accent);
		transition: width 120ms linear;
	}
</style>
