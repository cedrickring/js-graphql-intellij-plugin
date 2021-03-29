/*
 * Copyright (c) 2018-present, Jim Kynde Meyer
 * All rights reserved.
 * <p>
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.intellij.lang.jsgraphql.ide.validation;

import com.intellij.lang.annotation.Annotation;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.jsgraphql.GraphQLBundle;
import com.intellij.lang.jsgraphql.psi.*;
import com.intellij.lang.jsgraphql.psi.impl.GraphQLDirectivesAware;
import com.intellij.lang.jsgraphql.psi.impl.GraphQLTypeNameDefinitionOwnerPsiElement;
import com.intellij.lang.jsgraphql.psi.impl.GraphQLTypeNameExtensionOwnerPsiElement;
import com.intellij.lang.jsgraphql.schema.GraphQLSchemaInfo;
import com.intellij.lang.jsgraphql.schema.GraphQLSchemaProvider;
import com.intellij.lang.jsgraphql.types.GraphQLError;
import com.intellij.lang.jsgraphql.types.language.Node;
import com.intellij.lang.jsgraphql.types.language.SourceLocation;
import com.intellij.lang.jsgraphql.types.validation.ValidationError;
import com.intellij.lang.jsgraphql.types.validation.ValidationErrorType;
import com.intellij.lang.jsgraphql.types.validation.Validator;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.impl.source.tree.LeafElement;
import com.intellij.psi.impl.source.tree.TreeUtil;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtilCore;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;

public class GraphQLSchemaValidationAnnotator implements Annotator {
    private static final Logger LOG = Logger.getInstance(GraphQLSchemaValidationAnnotator.class);

    @Override
    public void annotate(@NotNull PsiElement psiElement, @NotNull AnnotationHolder annotationHolder) {
        if (!(psiElement instanceof GraphQLFile)) return;

        final GraphQLFile file = (GraphQLFile) psiElement;
        final Project project = psiElement.getProject();

        try {
            GraphQLSchemaInfo schemaInfo = GraphQLSchemaProvider.getInstance(project).getSchemaInfo(psiElement);
            if (schemaInfo.hasErrors()) {
                showSchemaErrors(annotationHolder, schemaInfo, file);
            } else {
                showDocumentErrors(annotationHolder, schemaInfo, file);
            }
        } catch (ProcessCanceledException e) {
            throw e;
        } catch (CancellationException e) {
            // ignore
        } catch (Exception e) {
            LOG.info(e);
        }
    }

    private void showDocumentErrors(@NotNull AnnotationHolder annotationHolder,
                                    @NotNull GraphQLSchemaInfo schemaInfo,
                                    @NotNull GraphQLFile file) {
        List<? extends GraphQLError> errors = validateQueryDocument(schemaInfo, file);

        for (GraphQLError error : errors) {
            if (!(error instanceof ValidationError)) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug(String.format("Ignored validation error: type=%s, message=%s", error.getClass().getName(), error.getMessage()));
                }
                continue;
            }

            final ValidationError validationError = (ValidationError) error;
            final ValidationErrorType validationErrorType = validationError.getValidationErrorType();
            if (validationErrorType == null) {
                continue;
            }

            switch (validationErrorType) {
                case DefaultForNonNullArgument:
                case WrongType:
                case SubSelectionRequired:
                case SubSelectionNotAllowed:
                case BadValueForDefaultArg:
                case InlineFragmentTypeConditionInvalid:
                case FragmentTypeConditionInvalid:
                case UnknownArgument:
                case NonInputTypeOnVariable:
                case MissingFieldArgument:
                case MissingDirectiveArgument:
                case VariableTypeMismatch:
                case MisplacedDirective:
                case UndefinedVariable:
                case UnusedVariable:
                case FragmentCycle:
                case FieldsConflict:
                case InvalidFragmentType:
                case LoneAnonymousOperationViolation:
                    processValidationError(annotationHolder, file, validationError, validationErrorType);
                    break;
                default:
                    // remaining rules should be handled in specific inspections / annotators
                    break;
            }
        }
    }

    private @NotNull List<? extends GraphQLError> validateQueryDocument(@NotNull GraphQLSchemaInfo schemaInfo, @NotNull GraphQLFile file) {
        return new Validator().validateDocument(schemaInfo.getSchema(), file.getDocument());
    }

    private void showSchemaErrors(@NotNull AnnotationHolder annotationHolder,
                                  @NotNull GraphQLSchemaInfo schemaInfo,
                                  @NotNull GraphQLFile file) {
        for (GraphQLError error : schemaInfo.getErrors()) {
            Collection<? extends PsiElement> elements = getElementsToAnnotate(file, error);
            for (PsiElement element : elements) {
                createErrorAnnotation(annotationHolder, error, element, error.getMessage());
            }
        }
    }

    private @NotNull Collection<PsiElement> getElementsToAnnotate(@NotNull PsiFile containingFile, @NotNull GraphQLError error) {
        String currentFileName = GraphQLPsiUtil.getFileName(containingFile);

        Node<?> node = error.getNode();
        if (node != null) {
            PsiElement element = node.getElement();
            if (element != null && element.isValid() && element.getContainingFile() == containingFile) {
                return Collections.singletonList(element);
            }
        }

        List<SourceLocation> locations = error.getLocations();
        if (locations == null) {
            return Collections.emptyList();
        }

        return ContainerUtil.mapNotNull(locations, location -> {
            if (!currentFileName.equals(location.getSourceName())) {
                return null;
            }

            PsiElement element = location.getElement();
            if (element != null && element.isValid() && element.getContainingFile() == containingFile) {
                return element;
            }

            int positionToOffset = location.getOffset();
            if (positionToOffset == -1) {
                return null;
            }

            PsiElement context = containingFile.getContext();
            if (context != null) {
                // injected file, so adjust the position
                positionToOffset = positionToOffset - context.getTextOffset();
            }
            return containingFile.findElementAt(positionToOffset);
        });
    }

    private void processValidationError(@NotNull AnnotationHolder annotationHolder,
                                        @NotNull PsiFile containingFile,
                                        @NotNull ValidationError validationError,
                                        @NotNull ValidationErrorType validationErrorType) {
        for (PsiElement element : getElementsToAnnotate(containingFile, validationError)) {
            final IElementType elementType = PsiUtilCore.getElementType(element);
            if (elementType == GraphQLElementTypes.SPREAD) {
                // graphql-java uses the '...' as source location on fragments, so find the fragment name or type condition
                final GraphQLFragmentSelection fragmentSelection = PsiTreeUtil.getParentOfType(element, GraphQLFragmentSelection.class);
                if (fragmentSelection != null) {
                    if (fragmentSelection.getFragmentSpread() != null) {
                        element = fragmentSelection.getFragmentSpread().getNameIdentifier();
                    } else if (fragmentSelection.getInlineFragment() != null) {
                        final GraphQLTypeCondition typeCondition = fragmentSelection.getInlineFragment().getTypeCondition();
                        if (typeCondition != null) {
                            element = typeCondition.getTypeName();
                        }
                    }
                }
            } else if (elementType == GraphQLElementTypes.AT) {
                // mark the directive and not only the '@'
                element = element.getParent();
            }

            if (element == null) {
                continue;
            }

            if (isInsideTemplateElement(element)) {
                // error due to template placeholder replacement, so we can ignore it for '___' replacement variables
                if (validationErrorType == ValidationErrorType.UndefinedVariable) {
                    continue;
                }
            }
            if (validationErrorType == ValidationErrorType.SubSelectionRequired) {
                // apollo client 2.5 doesn't require sub selections for client fields
                final GraphQLDirectivesAware directivesAware = PsiTreeUtil.getParentOfType(element, GraphQLDirectivesAware.class);
                if (directivesAware != null) {
                    boolean ignoreError = false;
                    for (GraphQLDirective directive : directivesAware.getDirectives()) {
                        if ("client".equals(directive.getName())) {
                            ignoreError = true;
                        }
                    }
                    if (ignoreError) {
                        continue;
                    }
                }
            }
            final String message = Optional.ofNullable(validationError.getDescription()).orElse(validationError.getMessage());
            createErrorAnnotation(annotationHolder, validationError, element, message);
        }
    }

    /**
     * Gets whether the specified element is inside a placeholder in a template
     */
    boolean isInsideTemplateElement(PsiElement psiElement) {
        return PsiTreeUtil.findFirstParent(
            psiElement, false,
            el -> el instanceof GraphQLTemplateDefinition || el instanceof GraphQLTemplateSelection || el instanceof GraphQLTemplateVariable
        ) != null;
    }

    private void createErrorAnnotation(@NotNull AnnotationHolder annotationHolder,
                                       @NotNull GraphQLError error,
                                       @NotNull PsiElement element,
                                       @Nullable String message) {
        if (message == null) return;

        if (GraphQLErrorFilter.isErrorIgnored(element.getProject(), error, element)) {
            return;
        }

        Annotation annotation = annotationHolder.createErrorAnnotation(getAnnotationAnchor(element), message);

        List<Node> references = error.getReferences();
        if (!references.isEmpty()) {
            annotation.setTooltip(createTooltip(error, message, references.size() > 1));
        }
    }

    @NotNull
    private String createTooltip(@NotNull GraphQLError error, @NotNull String message, boolean isMultiple) {
        StringBuilder sb = new StringBuilder();
        sb
            .append("<html>")
            .append(message)
            .append("<br/>");

        if (isMultiple) {
            sb.append("<br/>").append(GraphQLBundle.message("graphql.inspection.related.definitions"));
        }

        for (Node reference : error.getReferences()) {
            SourceLocation sourceLocation = reference.getSourceLocation();
            if (sourceLocation == null) continue;

            String navigationLabel;
            PsiElement referenceElement = reference.getElement();
            if (referenceElement != null && referenceElement.isValid()) {
                PsiElement annotationAnchor = getAnnotationAnchor(referenceElement);
                navigationLabel = GraphQLBundle.message("graphql.inspection.go.to.related.definition.name", annotationAnchor.getText());
            } else {
                navigationLabel = GraphQLBundle.message("graphql.inspection.go.to.related.definition.family.name");
            }

            sb
                .append("<br/>")
                .append("<a href=\"#navigation/")
                .append(sourceLocation.getNavigationLocation())
                .append("\">")
                .append(navigationLabel)
                .append("</a>");
        }
        sb.append("</html>");

        return sb.toString();
    }

    private @NotNull PsiElement getAnnotationAnchor(@NotNull PsiElement element) {
        if (element instanceof GraphQLTypeNameDefinitionOwnerPsiElement) {
            GraphQLTypeNameDefinition typeName = ((GraphQLTypeNameDefinitionOwnerPsiElement) element).getTypeNameDefinition();
            if (typeName != null) {
                return typeName.getNameIdentifier();
            }
        }
        if (element instanceof GraphQLTypeNameExtensionOwnerPsiElement) {
            GraphQLTypeName typeName = ((GraphQLTypeNameExtensionOwnerPsiElement) element).getTypeName();
            if (typeName != null) {
                return typeName.getNameIdentifier();
            }
        }
        if (element instanceof GraphQLInlineFragment) {
            GraphQLTypeCondition typeCondition = ((GraphQLInlineFragment) element).getTypeCondition();
            if (typeCondition != null) {
                element = typeCondition;
            }
        }
        if (element instanceof GraphQLTypeCondition) {
            GraphQLTypeName typeName = ((GraphQLTypeCondition) element).getTypeName();
            if (typeName != null) {
                return typeName;
            }
        }

        LeafElement leaf = TreeUtil.findFirstLeaf(element.getNode());
        if (leaf != null) {
            return leaf.getPsi();
        }
        return element;
    }

}
