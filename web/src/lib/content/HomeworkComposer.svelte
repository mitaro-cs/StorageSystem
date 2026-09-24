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
	import DifficultyPicker from './DifficultyPicker.svelte';
	import KindPicker from './KindPicker.svelte';
	import { isExam, kindOf, type HomeworkKind } from './kinds';

	interface Props {
		open: boolean;
		edit?: Homework | null;
		subjectId?: number | null;
		/** Тип нового задания: со страницы «Сессия» — сразу экзамен. */
		initialKind?: HomeworkKind;
		onsaved: (item: Homework) => void;
	}

	let {
		open = $bindable(),
		edit = null,
		subjectId = null,
		initialKind = 'homework',
		onsaved
	}: Props = $props();

	let subject = $state<number | null>(null);
	let title = $state('');
	let body = $state('');
	let due = $state('');
	let groupIds = $state<number[]>([]);
	let files = $state<FileInfo[]>([]);
	let difficulty = $state<number | null>(null);
	let kind = $state<HomeworkKind>('homework');
	let place = $state('');
	// Срок, который человек не трогал, подстраивается под тип: экзамен — в 9:00, задание — к 23:59.
	let dueTouched = $state(false);
	// Вложение ещё грузится — «Опубликовать» ждёт, иначе задание ушло бы без файла.
	let uploading = $state(0);
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

	/** По умолчанию — через неделю в 23:59; зачёт или экзамен — через две недели в 9:00. */
	function defaultDue(k: HomeworkKind): string {
		const n = new Date();
		const exam = isExam(k);
		return toLocalInput(
			new Date(
				n.getFullYear(),
				n.getMonth(),
				n.getDate() + (exam ? 14 : 7),
				exam ? 9 : 23,
				exam ? 0 : 59
			).getTime()
		);
	}

	function pickKind(k: HomeworkKind) {
		kind = k;
		if (!edit && !dueTouched) due = defaultDue(k);
	}

	$effect(() => {
		if (!open) return;
		subject = edit?.subject.id ?? subjectId ?? subjectOptions[0]?.id ?? null;
		title = edit?.title ?? '';
		body = edit?.bodyMd ?? '';
		// Не читаем kind после записи: иначе эффект зависел бы от него и сбрасывал выбор типа.
		const k = edit?.kind ?? initialKind;
		kind = k;
		place = edit?.place ?? '';
		dueTouched = false;
		due = edit ? toLocalInput(edit.dueAt) : defaultDue(k);
		groupIds = edit?.groups.map((g) => g.id) ?? [];
		files = edit ? [...edit.attachments] : [];
		difficulty = edit?.difficulty ?? null;
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
				attachments: files.map((f) => f.id),
				difficulty: difficulty ?? 0,
				kind,
				place: isExam(kind) ? place : ''
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
		<KindPicker bind:value={() => kind, pickKind} />
		<div class="grid">
			<div>
				<label class="label" for="hw-subject">Предмет</label>
				<select id="hw-subject" class="select" bind:value={subject} required disabled={!!edit}>
					{#each subjectOptions as s (s.id)}<option value={s.id}>{s.name}</option>{/each}
				</select>
			</div>
			<div>
				<label class="label" for="hw-due">{isExam(kind) ? 'Когда' : 'Сдать до'}</label>
				<input
					id="hw-due"
					class="input num"
					type="datetime-local"
					bind:value={due}
					oninput={() => (dueTouched = true)}
					required
				/>
			</div>
		</div>
		{#if isExam(kind)}
			<div>
				<label class="label" for="hw-place">Где <span class="faint">(необязательно)</span></label>
				<input
					id="hw-place"
					class="input"
					bind:value={place}
					maxlength="80"
					placeholder="ауд. 305 или ссылка на встречу"
				/>
			</div>
		{/if}
		{#if subjectOptions.length === 0}
			<p class="hint">Сначала создайте предмет в разделе «Предметы».</p>
		{/if}
		<div>
			<label class="label" for="hw-title">{isExam(kind) ? 'Название' : 'Что сделать'}</label>
			<input
				id="hw-title"
				class="input"
				bind:value={title}
				maxlength="200"
				required
				placeholder={kindOf(kind).placeholder}
			/>
		</div>
		<DifficultyPicker bind:value={difficulty} />
		<MarkdownEditor bind:value={body} label="Подробности" />
		<DropZone bind:files bind:uploading label="Вложения" />
		<GroupPicker options={targetOptions} bind:selected={groupIds} />
		{#if error}<p class="error-text" role="alert">{error}</p>{/if}
	</form>
	{#snippet footer()}
		<Button onclick={() => (open = false)}>Отмена</Button>
		<Button variant="primary" type="submit" form="hw-form" loading={busy || uploading > 0}>
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
