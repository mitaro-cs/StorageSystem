<script lang="ts">
	import { get, patch, post, qs } from '$lib/api';
	import { groupsWith, session } from '$lib/session.svelte';
	import { subjects } from '$lib/data.svelte';
	import { fromLocalInput, toLocalInput } from '$lib/format';
	import { toast } from '$lib/toasts.svelte';
	import type { FileInfo, Homework, Lesson } from '$lib/types';
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
		/** Задание к паре расписания: предмет, группа и срок — от неё. */
		lesson?: { id: number; startsAt: number; groupId: number; subjectId: number } | null;
		onsaved: (item: Homework) => void;
	}

	let {
		open = $bindable(),
		edit = null,
		subjectId = null,
		initialKind = 'homework',
		lesson = null,
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
	// Пара, к которой задание, и ближайшие пары выбранного предмета — выбрать срок одним нажатием.
	let lessonId = $state<number | null>(null);
	let upcoming = $state<{ id: number; startsAt: number }[]>([]);
	// Срок, который человек не трогал, подстраивается под тип: экзамен — в 9:00, задание — к 23:59.
	let dueTouched = $state(false);
	// Вложение ещё грузится — «Опубликовать» ждёт, иначе задание ушло бы без файла.
	let uploading = $state(0);
	let error = $state('');
	let busy = $state(false);

	const allowed = $derived(groupsWith('publish_homework'));
	// Написанное и приложенное не теряется от случайного клика мимо окна.
	const dirty = $derived(
		title !== (edit?.title ?? '') ||
			body !== (edit?.bodyMd ?? '') ||
			files.length !== (edit?.attachments.length ?? 0)
	);
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
		subject = edit?.subject.id ?? lesson?.subjectId ?? subjectId ?? subjectOptions[0]?.id ?? null;
		title = edit?.title ?? '';
		body = edit?.bodyMd ?? '';
		// Не читаем kind после записи: иначе эффект зависел бы от него и сбрасывал выбор типа.
		const k = edit?.kind ?? initialKind;
		kind = k;
		place = edit?.place ?? '';
		dueTouched = !!lesson;
		due = edit ? toLocalInput(edit.dueAt) : lesson ? toLocalInput(lesson.startsAt) : defaultDue(k);
		lessonId = edit?.lesson?.id ?? lesson?.id ?? null;
		groupIds = edit?.groups.map((g) => g.id) ?? (lesson ? [lesson.groupId] : []);
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

	// Ближайшие пары предмета (если у него есть расписание) — «К паре: чт, 25 сент., 09:30».
	$effect(() => {
		const s = subject;
		if (!open || !s || !subjects.list.find((x) => x.id === s)?.lessons) {
			upcoming = [];
			return;
		}
		const from = Date.now() - 2 * 3600_000;
		get<Lesson[]>(`/api/schedule${qs({ subject: s, from, to: from + 45 * 86_400_000 })}`)
			.then((list) => {
				if (subject === s) upcoming = list.slice(0, 4);
			})
			.catch(() => (upcoming = []));
	});
	const lessonChips = $derived.by(() => {
		const list = [...upcoming];
		if (lesson && !list.some((l) => l.id === lesson.id)) list.unshift(lesson);
		return list;
	});
	const chipTime = new Intl.DateTimeFormat('ru-RU', {
		weekday: 'short',
		day: 'numeric',
		month: 'short',
		hour: '2-digit',
		minute: '2-digit'
	});

	function toLesson(l: { id: number; startsAt: number }) {
		lessonId = l.id;
		due = toLocalInput(l.startsAt);
		dueTouched = true;
	}

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
				place: isExam(kind) ? place : '',
				lessonId: lessonId ?? 0
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

<Modal bind:open {dirty} title={edit ? 'Редактировать задание' : 'Новое задание'} wide>
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
		{#if lessonChips.length}
			<div class="to-lesson" role="group" aria-label="Срок — к паре">
				<span class="faint small">К паре:</span>
				{#each lessonChips as l (l.id)}
					<button
						type="button"
						class="pill small num"
						class:ink={lessonId === l.id}
						aria-pressed={lessonId === l.id}
						onclick={() => toLesson(l)}>{chipTime.format(l.startsAt)}</button
					>
				{/each}
				{#if lessonId}<button type="button" class="linklike small" onclick={() => (lessonId = null)}
						>без пары</button
					>{/if}
			</div>
		{/if}
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
	.to-lesson {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: 6px;
		margin-top: calc(-1 * var(--s2));
	}
	.to-lesson .pill {
		height: 32px;
		padding: 0 12px;
		font-size: 13px;
	}
	@media (max-width: 520px) {
		.grid {
			grid-template-columns: 1fr;
		}
	}
</style>
