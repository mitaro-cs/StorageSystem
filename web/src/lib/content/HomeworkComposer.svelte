<script lang="ts">
	import { patch, post } from '$lib/api';
	import { groupsWith, session } from '$lib/session.svelte';
	import { subjects } from '$lib/data.svelte';
	import { fromLocalInput, toLocalInput } from '$lib/format';
	import { toast } from '$lib/toasts.svelte';
	import type { FileInfo, Homework } from '$lib/types';
	import DropZone from './DropZone.svelte';
	import Modal from '$lib/ui/Modal.svelte';
	import Button from '$lib/ui/Button.svelte';
	import MarkdownEditor from '$lib/ui/MarkdownEditor.svelte';
	import GroupPicker from './GroupPicker.svelte';

	interface Props {
		open: boolean;
		edit?: Homework | null;
		subjectId?: number | null;
		onsaved: (item: Homework) => void;
	}

	let { open = $bindable(), edit = null, subjectId = null, onsaved }: Props = $props();

	let subject = $state<number | null>(null);
	let title = $state('');
	let body = $state('');
	let due = $state('');
	let groupIds = $state<number[]>([]);
	let files = $state<FileInfo[]>([]);
	let error = $state('');
	let busy = $state(false);

	const allowed = $derived(groupsWith('publish_homework'));
	const subjectOptions = $derived(
		subjects.list.filter(
			(s) => !s.archived && s.groups.some((g) => allowed.some((a) => a.id === g.id))
		)
	);
	const targetOptions = $derived(
		(subjects.list.find((s) => s.id === subject)?.groups ?? []).filter((g) =>
			allowed.some((a) => a.id === g.id)
		)
	);

	/** По умолчанию — через неделю в 23:59. */
	function defaultDue(): string {
		const n = new Date();
		return toLocalInput(new Date(n.getFullYear(), n.getMonth(), n.getDate() + 7, 23, 59).getTime());
	}

	$effect(() => {
		if (!open) return;
		subject = edit?.subject.id ?? subjectId ?? subjectOptions[0]?.id ?? null;
		title = edit?.title ?? '';
		body = edit?.bodyMd ?? '';
		due = edit ? toLocalInput(edit.dueAt) : defaultDue();
		groupIds = edit?.groups.map((g) => g.id) ?? [];
		files = edit ? [...edit.attachments] : [];
		error = '';
	});

	$effect(() => {
		const ids = targetOptions.map((g) => g.id);
		const kept = groupIds.filter((g) => ids.includes(g));
		if (kept.length === 0 && ids.length) {
			const pref = session.groupId;
			groupIds = pref !== null && ids.includes(pref) ? [pref] : ids;
		} else if (kept.length !== groupIds.length) groupIds = kept;
	});

	async function save(e: SubmitEvent) {
		e.preventDefault();
		busy = true;
		error = '';
		try {
			const payload = {
				subjectId: subject,
				title,
				body,
				dueAt: fromLocalInput(due),
				groupIds,
				attachments: files.map((f) => f.id)
			};
			const item = edit
				? await patch<Homework>(`/api/homework/${edit.id}`, payload)
				: await post<Homework>('/api/homework', payload);
			toast(edit ? 'Задание обновлено' : 'Задание опубликовано', 'ok');
			open = false;
			onsaved(item);
		} catch (err) {
			error = err instanceof Error ? err.message : 'Ошибка';
		} finally {
			busy = false;
		}
	}
</script>

<Modal bind:open title={edit ? 'Редактировать задание' : 'Новое задание'} wide>
	<form id="hw-form" class="stack form" onsubmit={save}>
		<div class="grid">
			<div>
				<label class="label" for="hw-subject">Предмет</label>
				<select id="hw-subject" class="select" bind:value={subject} required disabled={!!edit}>
					{#each subjectOptions as s (s.id)}<option value={s.id}>{s.name}</option>{/each}
				</select>
			</div>
			<div>
				<label class="label" for="hw-due">Сдать до</label>
				<input id="hw-due" class="input num" type="datetime-local" bind:value={due} required />
			</div>
		</div>
		{#if subjectOptions.length === 0}
			<p class="hint">Сначала создайте предмет в разделе «Предметы».</p>
		{/if}
		<div>
			<label class="label" for="hw-title">Что сделать</label>
			<input
				id="hw-title"
				class="input"
				bind:value={title}
				maxlength="200"
				required
				placeholder="Лабораторная №3"
			/>
		</div>
		<MarkdownEditor bind:value={body} label="Подробности" />
		<DropZone bind:files label="Вложения" />
		<GroupPicker options={targetOptions} bind:selected={groupIds} />
		{#if error}<p class="error-text" role="alert">{error}</p>{/if}
	</form>
	{#snippet footer()}
		<Button onclick={() => (open = false)}>Отмена</Button>
		<Button variant="primary" type="submit" form="hw-form" loading={busy}>
			{edit ? 'Сохранить' : 'Опубликовать'}
		</Button>
	{/snippet}
</Modal>

<style>
	.form {
		gap: var(--s4);
	}
	.grid {
		display: grid;
		grid-template-columns: 1fr 1fr;
		gap: var(--s3);
	}
	@media (max-width: 520px) {
		.grid {
			grid-template-columns: 1fr;
		}
	}
</style>
