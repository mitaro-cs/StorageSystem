<script lang="ts">
	import { untrack } from 'svelte';
	import { Megaphone, Pin, Siren, Users } from '@lucide/svelte';
	import { patch, post } from '$lib/api';
	import { groupsWith, session } from '$lib/session.svelte';
	import { subjects } from '$lib/data.svelte';
	import { toast } from '$lib/toasts.svelte';
	import type { FileInfo, NewsItem } from '$lib/types';
	import Modal from '$lib/ui/Modal.svelte';
	import Button from '$lib/ui/Button.svelte';
	import FormSection from '$lib/ui/FormSection.svelte';
	import SubjectPicker from '$lib/ui/SubjectPicker.svelte';
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
	// Фото ещё грузится – «Опубликовать» ждёт, иначе новость ушла бы без него.
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
		// Только при открытии: живое обновление (новые объекты предметов, группы) не стирает введённое.
		untrack(() => {
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

<Modal
	bind:open
	{dirty}
	title={edit ? 'Редактировать новость' : 'Новая новость'}
	subtitle={edit ? '' : 'Придёт уведомлением всем, кому адресована'}
	icon={Megaphone}
	wide
>
	<form id="news-form" class="stack form" onsubmit={save}>
		<input
			id="news-title"
			class="input big"
			aria-label="Заголовок"
			bind:value={title}
			maxlength="200"
			required
			placeholder="Заголовок"
		/>
		<MarkdownEditor bind:value={body} />
		<!-- Фото расписания, приказ, методичка – прямо в новости. Большие фото уменьшаются сами. -->
		<DropZone
			bind:files
			bind:uploading
			shrink
			label="Фото и файлы"
			hint="Фото, PDF, документы, презентации"
		/>
		{#if !edit}
			<FormSection title="Кому" icon={Users}>
				<SubjectPicker
					options={subjectOptions}
					bind:value={subject}
					none="Без предмета"
					label="Предмет"
				/>
				<GroupPicker options={targetOptions} bind:selected={groupIds} />
			</FormSection>
		{/if}
		<div class="flags">
			<label class="flag urgent" class:on={urgent}
				><input type="checkbox" bind:checked={urgent} /><Siren size={16} /> Срочно</label
			>
			<label class="flag" class:on={pinned}
				><input type="checkbox" bind:checked={pinned} /><Pin size={16} /> Закрепить</label
			>
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
		gap: var(--s3);
	}
	.big {
		height: 52px;
		font-size: 17px;
		font-weight: 600;
		border-radius: 14px;
	}
	.flags {
		display: flex;
		flex-wrap: wrap;
		gap: 8px;
	}
	.flag {
		display: inline-flex;
		align-items: center;
		gap: 7px;
		height: 38px;
		padding: 0 14px;
		border: 1px solid var(--border);
		border-radius: 999px;
		background: var(--surface);
		color: var(--text-2);
		font-size: 13.5px;
		font-weight: 600;
		cursor: pointer;
		transition: all var(--dur) var(--ease);
	}
	.flag {
		position: relative;
	}
	.flag input {
		position: absolute;
		inset: 0;
		width: 100%;
		height: 100%;
		margin: 0;
		opacity: 0;
		cursor: pointer;
	}
	.flag:has(input:focus-visible) {
		outline: 2px solid var(--focus);
		outline-offset: 2px;
	}
	.flag.on {
		border-color: var(--text);
		background: var(--text);
		color: var(--bg);
	}
	.flag.urgent.on {
		border-color: var(--danger);
		background: var(--danger);
		color: #fff;
	}
</style>
