<script lang="ts">
	import { FilePlus2, Files, Link2, MessageSquareText } from '@lucide/svelte';
	import { post } from '$lib/api';
	import { plural } from '$lib/format';
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
		/** Материал к паре расписания («слайды этой лекции»). */
		lessonId?: number | null;
		onsaved: () => void;
	}

	let {
		open = $bindable(),
		subjectId,
		folderId,
		suggest,
		lessonId = null,
		onsaved
	}: Props = $props();

	// Сообщение (0.6) – текст прямо в материалах: вставить из чата билеты, список литературы.
	let mode = $state<'file' | 'link' | 'note'>('file');
	const MODES = [
		{ id: 'file', label: 'Файлы', icon: Files },
		{ id: 'link', label: 'Ссылка', icon: Link2 },
		{ id: 'note', label: 'Сообщение', icon: MessageSquareText }
	] as const;
	let files = $state<FileInfo[]>([]);
	let uploading = $state(0);
	let url = $state('');
	let title = $state('');
	let description = $state('');
	/** Текст уведомления для пачки файлов (1.0.1): одно на всех, а не по штуке на файл. */
	let notice = $state('');
	let error = $state('');
	let busy = $state(false);
	const dirty = $derived(files.length > 0 || !!url || !!title || !!description || !!notice);

	$effect(() => {
		if (open) {
			files = [];
			url = title = description = notice = error = '';
		}
	});

	async function save(e: SubmitEvent) {
		e.preventDefault();
		busy = true;
		error = '';
		try {
			let created: Material[] = [];
			if (mode === 'note') {
				created.push(
					await post<Material>(`/api/subjects/${subjectId}/materials`, {
						kind: 'note',
						title,
						description,
						folderId,
						lessonId
					})
				);
			} else if (mode === 'link') {
				created.push(
					await post<Material>(`/api/subjects/${subjectId}/materials`, {
						kind: 'link',
						url,
						title,
						description,
						folderId,
						lessonId
					})
				);
			} else {
				if (files.length === 0) throw new Error('Добавьте хотя бы один файл');
				// Пачка – одним запросом и одним уведомлением. Без сети (файлы ждут на устройстве) –
				// по одному, через очередь офлайн-копии.
				if (files.length > 1 && files.every((f) => f.id > 0)) {
					created = await post<Material[]>(`/api/subjects/${subjectId}/materials/batch`, {
						fileIds: files.map((f) => f.id),
						description,
						folderId,
						lessonId,
						notice
					});
				} else {
					for (const f of files) {
						created.push(
							await post<Material>(`/api/subjects/${subjectId}/materials`, {
								kind: 'file',
								fileId: f.id,
								title: files.length === 1 ? title : '',
								description,
								folderId,
								lessonId
							})
						);
					}
				}
			}
			const pending = created.some((m) => m.status === 'pending');
			toast(
				pending
					? 'Отправлено на проверку старосте'
					: created.length > 1
						? `Добавлено: ${created.length}`
						: 'Материал добавлен',
				'ok'
			);
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

<Modal
	bind:open
	{dirty}
	title={suggest ? 'Предложить материал' : 'Добавить материал'}
	subtitle="Лекции, конспекты .md, PDF, презентации – или ссылка"
	icon={FilePlus2}
>
	<form id="material-form" class="stack form" onsubmit={save}>
		{#if suggest}
			<p class="note small">Староста проверит материал перед публикацией.</p>
		{/if}
		<div class="switch" role="tablist">
			{#each MODES as m (m.id)}
				<button
					type="button"
					role="tab"
					aria-selected={mode === m.id}
					class:on={mode === m.id}
					onclick={() => (mode = m.id)}><m.icon size={18} /><span>{m.label}</span></button
				>
			{/each}
		</div>
		{#if mode === 'file'}
			<DropZone bind:files bind:uploading max={100} />
		{:else if mode === 'note'}
			<!-- Текст сообщения – сразу, название не обязательно: возьмём первую строку. -->
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
		{#if mode === 'file' && files.length > 1}
			<div>
				<label class="label" for="m-notice"
					>Текст уведомления <span class="faint">(необязательно)</span></label
				>
				<input
					id="m-notice"
					class="input"
					bind:value={notice}
					maxlength="200"
					placeholder={`Добавлено ${files.length} ${plural(files.length, ['материал', 'материала', 'материалов'])}`}
				/>
			</div>
		{/if}
		{#if mode !== 'file' || files.length <= 1}
			<div>
				<label class="label" for="m-title"
					>Название <span class="faint">(необязательно)</span></label
				>
				<input id="m-title" class="input" bind:value={title} maxlength="200" />
			</div>
		{/if}
		{#if mode === 'note'}
			<div>
				<label class="label" for="m-text">Текст сообщения</label>
				<textarea
					id="m-text"
					class="textarea"
					rows="8"
					bind:value={description}
					maxlength="8000"
					placeholder="Напишите или вставьте – например, билеты к экзамену из чата"
					required></textarea>
			</div>
		{:else}
			<div>
				<label class="label" for="m-desc">Описание <span class="faint">(необязательно)</span></label
				>
				<textarea id="m-desc" class="textarea" rows="3" bind:value={description} maxlength="2000"
				></textarea>
			</div>
		{/if}
		{#if error}<p class="error-text" role="alert">{error}</p>{/if}
	</form>
	{#snippet footer()}
		<Button onclick={() => (open = false)}>Отмена</Button>
		<Button variant="primary" type="submit" form="material-form" loading={busy || uploading > 0}
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
		display: grid;
		grid-template-columns: repeat(3, 1fr);
		gap: 8px;
	}
	.switch button {
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		gap: 4px;
		height: 64px;
		border: 1px solid var(--border);
		border-radius: 14px;
		background: var(--surface);
		color: var(--text-2);
		font: inherit;
		font-size: 13px;
		font-weight: 600;
		transition: all var(--dur) var(--ease);
	}
	.switch button:hover {
		border-color: var(--border-strong);
	}
	.switch .on {
		border-color: var(--accent);
		background: color-mix(in srgb, var(--accent) 10%, var(--surface));
		color: var(--text);
		box-shadow: 0 0 0 1px var(--accent);
	}
</style>
