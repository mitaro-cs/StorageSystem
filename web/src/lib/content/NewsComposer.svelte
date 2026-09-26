<script lang="ts">
	import { patch, post } from '$lib/api';
	import { groupsWith, session } from '$lib/session.svelte';
	import { subjects } from '$lib/data.svelte';
	import { toast } from '$lib/toasts.svelte';
	import type { FileInfo, NewsItem } from '$lib/types';
	import Modal from '$lib/ui/Modal.svelte';
	import Button from '$lib/ui/Button.svelte';
	import MarkdownEditor from '$lib/ui/MarkdownEditor.svelte';
	import GroupPicker from './GroupPicker.svelte';
	import DropZone from './DropZone.svelte';

	interface Props {
		open: boolean;
		edit?: NewsItem | null;
		subjectId?: number | null;
		onsaved: (item: NewsItem) => void;
	}

	let { open = $bindable(), edit = null, subjectId = null, onsaved }: Props = $props();

	let title = $state('');
	let body = $state('');
	let subject = $state<number | null>(null);
	let groupIds = $state<number[]>([]);
	let pinned = $state(false);
	let urgent = $state(false);
	let files = $state<FileInfo[]>([]);
	// Фото ещё грузится — «Опубликовать» ждёт, иначе новость ушла бы без него.
	let uploading = $state(0);
	let error = $state('');
	let busy = $state(false);

	const allowed = $derived(groupsWith('publish_news'));
	// Написанное не теряется от случайного клика мимо окна.
	const dirty = $derived(
		title !== (edit?.title ?? '') ||
			body !== (edit?.bodyMd ?? '') ||
			files.length !== (edit?.attachments?.length ?? 0)
	);
	const subjectOptions = $derived(
		subjects.list.filter(
			(s) => !s.archived && s.groups.some((g) => allowed.some((a) => a.id === g.id))
		)
	);
	const targetOptions = $derived.by(() => {
		const s = subjects.list.find((x) => x.id === subject);
		const base = s ? s.groups : allowed.map((g) => ({ id: g.id, name: g.name }));
		return base.filter((g) => allowed.some((a) => a.id === g.id));
	});

	$effect(() => {
		if (!open) return;
		title = edit?.title ?? '';
		body = edit?.bodyMd ?? '';
		subject = edit?.subject?.id ?? subjectId;
		pinned = edit?.pinned ?? false;
		urgent = edit?.urgent ?? false;
		files = edit?.attachments ? [...edit.attachments] : [];
		error = '';
		const preferred = session.groupId;
		groupIds = edit
			? edit.groups.map((g) => g.id)
			: preferred !== null && allowed.some((g) => g.id === preferred)
				? [preferred]
				: allowed.map((g) => g.id).slice(0, 1);
	});

	$effect(() => {
		// При смене предмета оставляем только группы, связанные с ним.
		const ids = targetOptions.map((g) => g.id);
		const kept = groupIds.filter((g) => ids.includes(g));
		if (kept.length !== groupIds.length) groupIds = kept.length ? kept : ids.slice(0, 1);
	});

	async function save(e: SubmitEvent) {
		e.preventDefault();
		busy = true;
		error = '';
		try {
			const payload = {
				title,
				body,
				subjectId: subject,
				groupIds,
				pinned,
				urgent,
				attachments: files.map((f) => f.id)
			};
			const item = edit
				? await patch<NewsItem>(`/api/news/${edit.id}`, payload)
				: await post<NewsItem>('/api/news', payload);
			toast(edit ? 'Новость обновлена' : 'Новость опубликована', 'ok');
			open = false;
			onsaved(item);
		} catch (err) {
			error = err instanceof Error ? err.message : 'Ошибка';
		} finally {
			busy = false;
		}
	}
</script>

<Modal bind:open {dirty} title={edit ? 'Редактировать новость' : 'Новая новость'} wide>
	<form id="news-form" class="stack form" onsubmit={save}>
		<div>
			<label class="label" for="news-title">Заголовок</label>
			<input id="news-title" class="input" bind:value={title} maxlength="200" required />
		</div>
		<MarkdownEditor bind:value={body} />
		<!-- Фото расписания, приказ, методичка — прямо в новости. Большие фото уменьшаются сами. -->
		<DropZone
			bind:files
			bind:uploading
			shrink
			label="Фото и файлы"
			hint="Фото, PDF, документы, презентации"
		/>
		{#if !edit}
			<div>
				<label class="label" for="news-subject">Предмет</label>
				<select id="news-subject" class="select" bind:value={subject}>
					<option value={null}>Без предмета</option>
					{#each subjectOptions as s (s.id)}<option value={s.id}>{s.name}</option>{/each}
				</select>
			</div>
			<GroupPicker options={targetOptions} bind:selected={groupIds} />
		{/if}
		<div class="row flags">
			<label class="check"><input type="checkbox" bind:checked={urgent} /> Срочно</label>
			<label class="check"><input type="checkbox" bind:checked={pinned} /> Закрепить</label>
		</div>
		{#if error}<p class="error-text" role="alert">{error}</p>{/if}
	</form>
	{#snippet footer()}
		<Button onclick={() => (open = false)}>Отмена</Button>
		<Button
			variant="primary"
			type="submit"
			form="news-form"
			loading={busy}
			disabled={uploading > 0}
		>
			{edit ? 'Сохранить' : 'Опубликовать'}
		</Button>
	{/snippet}
</Modal>

<style>
	.form {
		gap: var(--s4);
	}
	.flags {
		gap: var(--s5);
	}
</style>
