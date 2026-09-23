<script lang="ts">
	import { post } from '$lib/api';
	import { toast } from '$lib/toasts.svelte';
	import type { FileInfo, Material } from '$lib/types';
	import Modal from '$lib/ui/Modal.svelte';
	import Button from '$lib/ui/Button.svelte';
	import DropZone from './DropZone.svelte';

	interface Props {
		open: boolean;
		subjectId: number;
		folderId: number | null;
		suggest: boolean;
		onsaved: () => void;
	}

	let { open = $bindable(), subjectId, folderId, suggest, onsaved }: Props = $props();

	let mode = $state<'file' | 'link'>('file');
	let files = $state<FileInfo[]>([]);
	let url = $state('');
	let title = $state('');
	let description = $state('');
	let error = $state('');
	let busy = $state(false);

	$effect(() => {
		if (open) {
			files = [];
			url = title = description = error = '';
		}
	});

	async function save(e: SubmitEvent) {
		e.preventDefault();
		busy = true;
		error = '';
		try {
			let created: Material[] = [];
			if (mode === 'link') {
				created.push(
					await post<Material>(`/api/subjects/${subjectId}/materials`, {
						kind: 'link',
						url,
						title,
						description,
						folderId
					})
				);
			} else {
				if (files.length === 0) throw new Error('Добавьте хотя бы один файл');
				for (const f of files) {
					created.push(
						await post<Material>(`/api/subjects/${subjectId}/materials`, {
							kind: 'file',
							fileId: f.id,
							title: files.length === 1 ? title : '',
							description,
							folderId
						})
					);
				}
			}
			const pending = created.some((m) => m.status === 'pending');
			toast(pending ? 'Отправлено на проверку старосте' : 'Материал добавлен', 'ok');
			open = false;
			created = [];
			onsaved();
		} catch (err) {
			error = err instanceof Error ? err.message : 'Ошибка';
		} finally {
			busy = false;
		}
	}
</script>

<Modal bind:open title={suggest ? 'Предложить материал' : 'Добавить материал'}>
	<form id="material-form" class="stack form" onsubmit={save}>
		{#if suggest}
			<p class="note small">Староста проверит материал перед публикацией.</p>
		{/if}
		<div class="switch" role="tablist">
			<button
				type="button"
				role="tab"
				aria-selected={mode === 'file'}
				class:on={mode === 'file'}
				onclick={() => (mode = 'file')}>Файлы</button
			>
			<button
				type="button"
				role="tab"
				aria-selected={mode === 'link'}
				class:on={mode === 'link'}
				onclick={() => (mode = 'link')}>Ссылка</button
			>
		</div>
		{#if mode === 'file'}
			<DropZone bind:files />
		{:else}
			<div>
				<label class="label" for="m-url">Адрес</label>
				<input
					id="m-url"
					class="input"
					type="url"
					bind:value={url}
					placeholder="https://"
					required
				/>
			</div>
		{/if}
		{#if mode === 'link' || files.length <= 1}
			<div>
				<label class="label" for="m-title"
					>Название <span class="faint">(необязательно)</span></label
				>
				<input id="m-title" class="input" bind:value={title} maxlength="200" />
			</div>
		{/if}
		<div>
			<label class="label" for="m-desc">Описание <span class="faint">(необязательно)</span></label>
			<textarea id="m-desc" class="textarea" rows="3" bind:value={description} maxlength="2000"
			></textarea>
		</div>
		{#if error}<p class="error-text" role="alert">{error}</p>{/if}
	</form>
	{#snippet footer()}
		<Button onclick={() => (open = false)}>Отмена</Button>
		<Button variant="primary" type="submit" form="material-form" loading={busy}
			>{suggest ? 'Отправить' : 'Добавить'}</Button
		>
	{/snippet}
</Modal>

<style>
	.form {
		gap: var(--s4);
	}
	.note {
		padding: 10px 12px;
		border-radius: var(--r-s);
		background: var(--amber-soft);
		color: var(--amber);
	}
	.switch {
		display: flex;
		gap: 4px;
		padding: 4px;
		background: var(--surface-2);
		border-radius: var(--r);
	}
	.switch button {
		flex: 1;
		height: 32px;
		border: 0;
		border-radius: 9px;
		background: transparent;
		color: var(--text-2);
		font-weight: 550;
	}
	.switch .on {
		background: var(--surface);
		color: var(--text);
		box-shadow: var(--shadow-1);
	}
</style>
