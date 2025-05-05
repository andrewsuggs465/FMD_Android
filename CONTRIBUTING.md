# Contributing to FMD

Thanks for taking the time to improve FMD!

Contributions in all fields of development are welcome.

If you are unsure where to start here are some ideas:
- look at the issues of the Project
- start translating the application into your language
- imlement a new feature you had in mind.

If you have questions or your merge request isn't ready you can upload it and mark it as a WOP(WorkInProgress).

## How to start and upload your changes?

1. Fork the Project
2. Make your changes to your Fork
3. Create a MergeRequest on GitLab and write a short description what you have improved
4. I will check it and if everything seems fine you have contributed. :description

## Translating

Translations are hosted on [Weblate](https://hosted.weblate.org/projects/fmd/fmd-android/).

The source language is English, located in `values/strings.xml`.
English translations should always be manually committed to Gitlab to keep the file clean and organised.
English is synced up from Gitlab to Weblate, using a webhook on every commit to master.

All other languages should be added and edited on Weblate.
They are then synced down from Weblate to Gitlab, Weblate will automatically open Merge Requests.

### Fixing translation conflicts

TLDR:

```
git remote add weblate https://hosted.weblate.org/git/fmd/fmd-android
git checkout -b fix-weblate
git remote update weblate
git merge --strategy-option theirs weblate/master
git push --set-upstream origin fix-weblate
```

Sometimes there are conflicts with Weblate (and it is not always clear where they come from...).

To fix them, follow Weblate's instructions here: https://docs.weblate.org/en/latest/faq.html#merge.
There are two exceptions: use a merge strategy, and don't rebase.

When you do the merge, you will get conflicts.
Manually trying to resolve them will not always work straight away.
Therefore the recommended option is to tell git so automatically resolve the conflicts
in favour of what the Weblate branch has:

```bash
git merge --strategy-option theirs weblate/master
```

To check that this worked, and that there are no more conflicts, simply merge a second time: `git merge weblate/master`.
This should print `Already up-to-date.`.

Also, don't rebase (as per Weblate instructions).
Instead, just do all of this on a fresh branch checked out on top of master.

Then push your branch, merge it into master, and unlock Weblate again.

