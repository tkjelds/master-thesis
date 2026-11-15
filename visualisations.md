We should do this for the differenct metrics that we collect both for software quality metrics (Sokrates) and code errors (codeql and infer). We shouldn't mix the the two categoires of metrics for the visualisations.

The order of the models/client should be 

Harness 

Api (openai and anthropic)

Local models.

the ordering should be consistent on all the diagrams.

We do one for run 1 and then one where we average out the three runs.
| Client | Model | Quality metics 1 | ... | Quality metric n |
| Harness | Sol | x_1 | ... | 

We also do one where we seperate out the categories ie. Datastructures, Advanced datastructures and Novel problems (in that order)

We should also have a heat map thing for problems something maybe like

| | Client/model\_1 | ... | client/model\_n |
| Datastructure\_1 | Metric | Metric | 
| ... | ... | ... | ...| 
| Novel_problem\_1 | ...| ... | ... | 

Then we do a heatmap for every metrics Depending on the size, we might make for every category of problem.

For the heatmap one, we make one for run 1 and then an averaged one accross the 3 runs.

We should also make other versions of the 

Keep the style of the already generated visualisations.

