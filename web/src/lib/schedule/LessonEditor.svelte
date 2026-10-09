<script lang="ts">
	import DateField from '$lib/ui/DateField.svelte';
	import TimeField from '$lib/ui/TimeField.svelte';
	import { untrack } from 'svelte';
	import { CalendarPlus, Clock, MapPin, NotebookPen, Repeat } from '@lucide/svelte';
	import { get, patch, post, qs } from '$lib/api';
	import { subjects } from '$lib/data.svelte';
	import { plural } from '$lib/format';
	import { toast } from '$lib/toasts.svelte';
	import type { Lesson, LessonKind } from '$lib/types';
	import Button from '$lib/ui/Button.svelte';
	import Modal from '$lib/ui/Modal.svelte';
	import FormSection from '$lib/ui/FormSection.svelte';
	import SubjectPicker from '$lib/ui/SubjectPicker.svelte';
	import { KIND_COLORS, LESSON_KINDS } from './lessons';

	// Пара вручную: предмет, вид, день и время, аудитория, тема; новую можно повторить на несколько
	// недель вперёд. Или правка пары – edit.
	let {
		open = $bindable(),
		groupId,
		edit = null,
		day = Date.now(),
		onsaved
	}: {
		open: boolean;
		groupId: number;
		edit?: Lesson | null;
		/** День новой пары по умолчанию. */
		day?: number;
		onsaved: (l: Lesson) => void;
	} = $props();

	/** Пара в вузе – 1 ч 35 мин (две половины и перерыв). */
	const LENGTH = 95;

	let subjectId = $state<number | null>(null);
	let title = $state('');
	let kind = $state<LessonKind>('lecture');
	let date = $state('');
	let start = $state('09:30');
	let end = $state('11:05');
	let endTouched = $state(false);
	let place = $state('');
	let teacher = $state('');
	let note = $state('');
	let repeat = $state(0);
	let busy = $state(false);
	let error = $state('');

	const options = $derived(
		subjects.list.filter((s) => !s.archived && s.groups.some((g) => g.id === groupId))
	);
	const p2 = (n: number) => String(n).padStart(2, '0');
	const dateOf = (ms: number) => {
		const d = new Date(ms);
		return `${d.getFullYear()}-${p2(d.getMonth() + 1)}-${p2(d.getDate())}`;
	};
	const timeOf = (ms: number) => {
		const d = new Date(ms);
		return `${p2(d.getHours())}:${p2(d.getMinutes())}`;
	};
	const dirty = $derived(!!(title || place || note) || (edit ? note !== edit.note : false));

	$effect(() => {
		if (!open) return;
		untrack(() => {
			subjectId = edit ? (edit.subject?.id ?? null) : (options[0]?.id ?? null);
			title = edit && !edit.subject ? edit.title : '';
			kind = edit?.kind ?? 'lecture';
			date = dateOf(edit?.startsAt ?? day);
			start = edit ? timeOf(edit.startsAt) : '09:30';
			end = edit ? timeOf(edit.endsAt) : '11:05';
			endTouched = !!edit;
			place = edit?.place ?? '';
			teacher = edit?.teacher ?? '';
			note = edit?.note ?? '';
			repeat = 0;
			error = '';
		});
	});

	// Вид «Занятие» (other) – только для пар из файла расписания, вручную его не выбирают.
	const kinds = LESSON_KINDS.filter((k) => k.value !== 'other');
	const REPEATS = [0, 1, 3, 7, 15, 17];

	// Время пар этой группы – из расписания: «09:30–11:00» одним нажатием.
	let slots = $state<{ start: string; end: string }[]>([]);
	$effect(() => {
		if (!open) return;
		const now = Date.now();
		const day = 86_400_000;
		get<Lesson[]>(
			`/api/schedule${qs({ group: groupId, from: now - 60 * day, to: now + 60 * day })}`
		)
			.then((list) => {
				const count: Record<string, number> = {};
				for (const l of list) {
					const k = `${timeOf(l.startsAt)}|${timeOf(l.endsAt)}`;
					count[k] = (count[k] ?? 0) + 1;
				}
				slots = Object.entries(count)
					.sort((a, b) => b[1] - a[1])
					.slice(0, 6)
					.map(([k]) => ({ start: k.split('|')[0], end: k.split('|')[1] }))
					.sort((a, b) => a.start.localeCompare(b.start));
			})
			.catch(() => (slots = []));
	});
	function pickSlot(t: { start: string; end: string }) {
		start = t.start;
		end = t.end;
		endTouched = true;
	}

	/** Начало сдвинули – конец едет следом, пока его не трогали. */
	function moveStart(v: string) {
		start = v;
		if (endTouched || !/^\d{2}:\d{2}$/.test(v)) return;
		const [h, m] = v.split(':').map(Number);
		const t = h * 60 + m + LENGTH;
		end = `${p2(Math.floor(t / 60) % 24)}:${p2(t % 60)}`;
	}

	async function save(e: SubmitEvent) {
		e.preventDefault();
		error = '';
		const startsAt = new Date(`${date}T${start}`).getTime();
		const endsAt = new Date(`${date}T${end}`).getTime();
		if (!Number.isFinite(startsAt) || !Number.isFinite(endsAt)) {
			error = 'Укажите день и время';
			return;
		}
		if (endsAt <= startsAt) {
			error = 'Пара заканчивается позже, чем начинается';
			return;
		}
		busy = true;
		try {
			const body = {
				subjectId: subjectId ?? 0,
				title: subjectId ? '' : title,
				kind,
				startsAt,
				endsAt,
				place,
				teacher,
				note
			};
			if (edit) {
				const l = await patch<Lesson>(`/api/lessons/${edit.id}`, body);
				toast('Пара изменена', 'ok');
				open = false;
				onsaved(l);
			} else {
				const made = await post<Lesson[]>(`/api/groups/${groupId}/lessons`, {
					...body,
					repeatWeeks: repeat
				});
				toast(made.length > 1 ? `Добавлено пар: ${made.length}` : 'Пара добавлена', 'ok');
				open = false;
				onsaved(made[0]);
			}
		} catch (err) {
			error = err instanceof Error ? err.message : 'Не сохранилось';
		} finally {
			busy = false;
		}
	}
</script>

<Modal
	bind:open
	{dirty}
	title={edit ? 'Изменить пару' : 'Новая пара'}
	subtitle={edit ? '' : 'Появится в расписании группы и на странице предмета'}
	icon={CalendarPlus}
	tone={KIND_COLORS[kind]}
	wide
>
	<form id="lesson-form" class="stack form" onsubmit={save}>
		<div class="kinds" role="radiogroup" aria-label="Вид пары">
			{#each kinds as k (k.value)}
				<button
					type="button"
					role="radio"
					aria-checked={kind === k.value}
					class:on={kind === k.value}
					style:--k={KIND_COLORS[k.value]}
					onclick={() => (kind = k.value)}><i></i>{k.label}</button
				>
			{/each}
		</div>
		<FormSection title="Предмет">
			<SubjectPicker {options} bind:value={subjectId} none="Без предмета" />
			{#if !subjectId}
				<div>
					<label class="label" for="l-title">Название</label>
					<input
						id="l-title"
						class="input"
						bind:value={title}
						maxlength="200"
						required
						placeholder="Кураторский час"
					/>
				</div>
			{/if}
		</FormSection>
		<FormSection title="Когда" icon={Clock}>
			<div class="grid three">
				<div>
					<label class="label" for="l-date">День</label>
					<DateField id="l-date" bind:value={date} required />
				</div>
				<div>
					<label class="label" for="l-start">Начало</label>
					<TimeField id="l-start" value={start} onchange={moveStart} required />
				</div>
				<div>
					<label class="label" for="l-end">Конец</label>
					<TimeField id="l-end" bind:value={end} onchange={() => (endTouched = true)} required />
				</div>
			</div>
			{#if slots.length}
				<div class="slots" role="group" aria-label="Время пар группы">
					{#each slots as t (t.start + t.end)}
						<button
							type="button"
							class="slot num"
							class:on={start === t.start && end === t.end}
							aria-pressed={start === t.start && end === t.end}
							onclick={() => pickSlot(t)}>{t.start}–{t.end}</button
						>
					{/each}
				</div>
			{/if}
			{#if !edit}
				<div class="repeat" role="radiogroup" aria-label="Повторять">
					<span class="lbl"><Repeat size={14} /> Повторять</span>
					{#each REPEATS as n (n)}
						<button
							type="button"
							role="radio"
							aria-checked={repeat === n}
							class="slot"
							class:on={repeat === n}
							onclick={() => (repeat = n)}
							>{n === 0
								? 'Один раз'
								: `${n + 1} ${plural(n + 1, ['неделю', 'недели', 'недель'])}`}</button
						>
					{/each}
				</div>
			{/if}
		</FormSection>
		<FormSection title="Где и кто" icon={MapPin} hint="необязательно">
			<div class="grid">
				<div>
					<label class="label" for="l-place">Аудитория</label>
					<input id="l-place" class="input" bind:value={place} maxlength="80" placeholder="Н-514" />
				</div>
				<div>
					<label class="label" for="l-teacher">Преподаватель</label>
					<input
						id="l-teacher"
						class="input"
						bind:value={teacher}
						maxlength="120"
						placeholder="Иванова И. И."
					/>
				</div>
			</div>
		</FormSection>
		<FormSection title="Тема и заметка" icon={NotebookPen} hint="необязательно">
			<textarea
				id="l-note"
				class="textarea"
				rows="3"
				aria-label="Тема и заметка"
				bind:value={note}
				maxlength="2000"
				placeholder="Лекция 3. Производные – принести калькулятор"></textarea>
		</FormSection>
		{#if error}<p class="error-text" role="alert">{error}</p>{/if}
	</form>
	{#snippet footer()}
		<Button onclick={() => (open = false)}>Отмена</Button>
		<Button variant="primary" type="submit" form="lesson-form" loading={busy}>
			{edit ? 'Сохранить' : 'Добавить пару'}
		</Button>
	{/snippet}
</Modal>

<style>
	.form {
		gap: var(--s3);
	}
	.kinds {
		display: flex;
		flex-wrap: wrap;
		gap: 6px;
	}
	.kinds button {
		display: inline-flex;
		align-items: center;
		gap: 7px;
		height: 36px;
		padding: 0 14px 0 12px;
		border: 1px solid var(--border);
		border-radius: 999px;
		background: var(--surface);
		color: var(--text-2);
		font: inherit;
		font-size: 13.5px;
		font-weight: 600;
		transition: all var(--dur) var(--ease);
	}
	.kinds i {
		width: 8px;
		height: 8px;
		border-radius: 50%;
		background: var(--k);
	}
	.kinds button.on {
		border-color: var(--k);
		background: color-mix(in srgb, var(--k) 14%, var(--surface));
		color: var(--text);
		box-shadow: 0 0 0 1px var(--k);
	}
	.slots,
	.repeat {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: 6px;
	}
	.lbl {
		display: inline-flex;
		align-items: center;
		gap: 5px;
		margin-right: 4px;
		font-size: 13px;
		color: var(--text-2);
	}
	.slot {
		height: 30px;
		padding: 0 11px;
		border: 1px solid var(--border);
		border-radius: 999px;
		background: var(--surface);
		color: var(--text-2);
		font: inherit;
		font-size: 12.5px;
		font-weight: 600;
	}
	.slot.on {
		border-color: var(--text);
		background: var(--text);
		color: var(--bg);
	}
	.grid {
		display: grid;
		grid-template-columns: 1fr 1fr;
		gap: var(--s3);
	}
	.grid.three {
		grid-template-columns: 1.4fr 1fr 1fr;
	}
	@media (max-width: 520px) {
		.grid,
		.grid.three {
			grid-template-columns: 1fr;
		}
	}
</style>
